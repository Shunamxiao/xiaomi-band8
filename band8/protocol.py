"""
小米手环8 (Xiaomi Smart Band 8) BLE 协议客户端骨架

已确认事实（来自 com.mi.health 3.59.2 反编译）:
  - GATT 服务: 0000FE95-0000-1000-8000-00805F9B34FB
  - 通道特征: 0x0051~0x005A (读写/通知成对), 主通道 0x53 写 / 0x52 通知
  - 帧结构: [seq u16 LE][type u8][payload]
      type 0 = CMD(dataType, frameCount)
      type 1 = ACK(status, seq...)
      type 2 = SINGLE_CMD(dataType, data)
      type 3 = SINGLE_ACK(status)
  - 认证模块: AuthModule2 module=130, apiLevel=2
      命令 0 查版本 / 1 本地绑定 / 2 云绑定 / 3 验签 / 4 请求随机数 / 6-7 解绑
      请求随机数 payload: [04][apiLevel u16 LE][02][本地公钥]
  - 曲线: sect163r2 (NIST B-163), 公钥 48 字节 = X(24) || Y(24)
  - 会话密钥: HKDF-HMAC-SHA256(info="miwear-auth"), 输出 64B
      DeviceKey[0:16] DeviceIV[32:36] AppKey[16:32] AppIV[36:40]
  - 数据加密: AES-CCM, MAC 32bit, nonce = 4B IV + 8B 计数器

待确认（下一阶段实证）:
  - 模块 ID 在帧中的携带位置（ChannelApiImpl 的 z3 头字段顺序）
  - 绑定设备复用已有密钥时的完整签名流程
"""

from __future__ import annotations

import struct
from dataclasses import dataclass
from typing import Optional

SERVICE_UUID = "0000fe95-0000-1000-8000-00805f9b34fb"
WRITE_CHAR = "00000053-0000-1000-8000-00805f9b34fb"
NOTIFY_CHAR = "00000052-0000-1000-8000-00805f9b34fb"

FRAME_CMD = 0
FRAME_ACK = 1
FRAME_SINGLE_CMD = 2
FRAME_SINGLE_ACK = 3

AUTH_MODULE_ID = 130
AUTH_API_LEVEL = 2

CMD_QUERY_VERSION = 0
CMD_START_LOCAL_BINDING = 1
CMD_START_CLOUD_BINDING = 2
CMD_VERIFY_SIGNATURE = 3
CMD_REQUEST_RANDOM = 4
CMD_AUTHORIZE = 5


def encode_single_cmd(data_type: int, payload: bytes, seq: int = 0) -> bytes:
    """单包命令帧: [seq u16 LE][0x02][dataType][payload]"""
    return struct.pack("<HBB", seq, FRAME_SINGLE_CMD, data_type) + payload


def encode_cmd(data_type: int, frame_count: int, seq: int = 0) -> bytes:
    """多包命令的首帧: [seq u16 LE][0x00][dataType][frameCount u16 LE]"""
    return struct.pack("<HBBH", seq, FRAME_CMD, data_type, frame_count)


def encode_ack(status: int, seqs: list[int], seq: int = 0) -> bytes:
    body = struct.pack("<BBH", FRAME_ACK, status, len(seqs))
    body += b"".join(struct.pack("<H", s) for s in seqs)
    return struct.pack("<H", seq) + body


@dataclass
class Frame:
    seq: int
    kind: int
    data: bytes

    @property
    def kind_name(self) -> str:
        return {
            FRAME_CMD: "CMD",
            FRAME_ACK: "ACK",
            FRAME_SINGLE_CMD: "SINGLE_CMD",
            FRAME_SINGLE_ACK: "SINGLE_ACK",
        }.get(self.kind, f"UNKNOWN({self.kind})")


def decode_frame(buf: bytes) -> Optional[Frame]:
    if len(buf) < 3:
        return None
    seq, kind = struct.unpack_from("<HB", buf, 0)
    return Frame(seq=seq, kind=kind, data=buf[3:])


def build_auth_random_request(public_key: bytes) -> bytes:
    """
    构造请求随机数命令（AuthModule2.requestAuth）
    payload = [04][apiLevel u16 LE][02][pubkey 48B]
    """
    assert len(public_key) == 48, "sect163r2 公钥必须是 48 字节 (X||Y)"
    payload = bytes([CMD_REQUEST_RANDOM]) + struct.pack("<H", AUTH_API_LEVEL) + bytes([2]) + public_key
    return encode_single_cmd(0, payload)


class BandChannel:
    """基于 bleak 的通道封装，负责收发与 ACK 处理"""

    def __init__(self, client, write_uuid=WRITE_CHAR, notify_uuid=NOTIFY_CHAR):
        self.client = client
        self.write_uuid = write_uuid
        self.notify_uuid = notify_uuid
        self.rx: list[Frame] = []

    async def open(self):
        await self.client.start_notify(self.notify_uuid, self._on_notify)

    async def close(self):
        try:
            await self.client.stop_notify(self.notify_uuid)
        except Exception:
            pass

    def _on_notify(self, _sender, data: bytearray):
        frame = decode_frame(bytes(data))
        if frame is not None:
            self.rx.append(frame)

    async def send(self, frame: bytes, response: bool = True):
        await self.client.write_gatt_char(self.write_uuid, frame, response=response)

    def pop(self) -> Optional[Frame]:
        return self.rx.pop(0) if self.rx else None


# ---------------------------------------------------------------- 加密层说明
# 会话建立后:
#   key = HKDF-SHA256(secret=ECDH(本地私钥, 手环公钥), salt=randomApp||randomDevice,
#                     info=b"miwear-auth", length=64)
#   encrypt = AES-CCM(key=AppKey, nonce=AppIV||counter_be8, mac_len=4, aad=None)
#   decrypt = AES-CCM(key=DeviceKey, nonce=DeviceIV||counter_be8, mac_len=4)
#
# 注意: sect163r2 是二元域曲线, Python cryptography 不支持;
# Android 端直接用 BouncyCastle (org.bouncycastle:bcprov-jdk18on),
# 其 ECDomainParameters 内置 sect163r2, 同时提供 CCM 与 HKDF。

