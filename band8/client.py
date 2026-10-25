"""
小米手环8 参考客户端（认证链路已验证通过）

已实测验证（2026-10-24，手环 m66nfc，MAC AA:BB:CC:DD:EE:FF）：
  1. 发送 cmd 26（app verify）后，手环返回 16B 设备随机数 + 32B 签名
  2. HKDF(ikm=encrypt_key, salt=appRand||devRand, info="miwear-auth", 64B) 派生会话密钥
  3. HMAC-SHA256(deviceKey, devRand||appRand) 与手环签名逐字节一致
  4. 回 cmd 27（app confirm）后，手环返回 code=1（认证成功）
  5. 之后的帧为 AES-CCM(4B MAC) 加密，nonce = appIV(4) + 0(4) + counter(4)

待完成：业务命令号（模块列表 / 电量 / 通知）。见 docs/architecture.md。
"""

import asyncio
import hashlib
import hmac
import os
import struct
from dataclasses import dataclass, field

from bleak import BleakClient
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESCCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

SERVICE = "0000fe95-0000-1000-8000-00805f9b34fb"
CH_DATA = "00000052-0000-1000-8000-00805f9b34fb"   # 主机 -> 手环 数据
CH_IN = "00000051-0000-1000-8000-00805f9b34fb"     # 手环 -> 主机 数据（同时也是主机回 ACK 的通道）

FRAME_SINGLE_CMD = 0x02
FRAME_SINGLE_ACK = 0x03

DT_PLAIN_OLD = 0x00
DT_ENCRYPTED = 0x01
DT_PROTOBUF = 0x02

CMD_APP_VERIFY = 26
CMD_APP_CONFIRM = 27


def _varint(value: int) -> bytes:
    out = bytearray()
    while True:
        part = value & 0x7F
        value >>= 7
        if value:
            out.append(part | 0x80)
        else:
            out.append(part)
            return bytes(out)


def pb_varint(field_no: int, value: int) -> bytes:
    return _varint(field_no << 3) + _varint(value)


def pb_bytes(field_no: int, data: bytes) -> bytes:
    return _varint((field_no << 3) | 2) + _varint(len(data)) + data


def _read_varint(buf: bytes, pos: int):
    result = 0
    shift = 0
    while True:
        byte = buf[pos]
        pos += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, pos
        shift += 7


def pb_parse(buf: bytes) -> dict:
    """极简 protobuf 解析，返回 {字段号: [("v", int) | ("b", bytes) | ("f", float)]}"""
    pos = 0
    fields: dict[int, list] = {}
    while pos < len(buf):
        tag, pos = _read_varint(buf, pos)
        field_no, wire = tag >> 3, tag & 7
        if wire == 0:
            value, pos = _read_varint(buf, pos)
            fields.setdefault(field_no, []).append(("v", value))
        elif wire == 2:
            length, pos = _read_varint(buf, pos)
            fields.setdefault(field_no, []).append(("b", buf[pos:pos + length]))
            pos += length
        elif wire == 5:
            fields.setdefault(field_no, []).append(("f", struct.unpack("<f", buf[pos:pos + 4])[0]))
            pos += 4
        else:
            break
    return fields


def build_pyt(command: int, payload: bytes = b"", version: int = 1) -> bytes:
    """构造 pyt 信封：field1=version, field2=command, field3=payload"""
    return pb_varint(1, version) + pb_varint(2, command) + pb_bytes(3, payload)


def parse_pyt(buf: bytes):
    fields = pb_parse(buf)
    version = fields.get(1, [("v", None)])[0][1]
    command = fields.get(2, [("v", None)])[0][1]
    payload = fields.get(3, [("b", b"")])[0][1]
    return version, command, payload


def frame(data_type: int, body: bytes, seq: int = 0) -> bytes:
    return struct.pack("<HBB", seq, FRAME_SINGLE_CMD, data_type) + body


@dataclass
class SessionKeys:
    device_key: bytes
    app_key: bytes
    device_iv: bytes
    app_iv: bytes
    counter: int = 0
    extra: dict = field(default_factory=dict)

    def encrypt(self, plaintext: bytes) -> bytes:
        """返回 [counter u16 LE][ciphertext||4B MAC]"""
        self.counter += 1
        nonce = self.app_iv + struct.pack("<I", 0) + struct.pack("<I", self.counter)
        cipher = AESCCM(self.app_key, tag_length=4).encrypt(nonce, plaintext, None)
        return struct.pack("<H", self.counter) + cipher

    def decrypt(self, body: bytes) -> bytes:
        # 手环下行帧不带计数器，nonce 固定为 deviceIV + 8 字节 0
        nonce = self.device_iv + b"\x00" * 8
        return AESCCM(self.device_key, tag_length=4).decrypt(nonce, body, None)


class Band8Client:
    def __init__(self, address: str, device_key: bytes):
        assert len(device_key) == 16, "device key 必须是 16 字节（device_db 的 encrypt_key）"
        self.address = address
        self.device_key = device_key
        self.client: BleakClient | None = None
        self.keys: SessionKeys | None = None
        self._inbox: asyncio.Queue = asyncio.Queue()

    async def __aenter__(self):
        self.client = BleakClient(self.address, timeout=20)
        await self.client.connect()
        await self.client.start_notify(CH_IN, self._on_incoming)
        await self.client.start_notify(CH_DATA, lambda _c, _d: None)
        await asyncio.sleep(0.4)
        await self.authenticate()
        return self

    async def __aexit__(self, *exc):
        if self.client:
            try:
                await self.client.stop_notify(CH_IN)
            except Exception:
                pass
            await self.client.disconnect()

    async def _on_incoming(self, _char, data: bytearray):
        await self._inbox.put(bytes(data))

    async def _request(self, data_type: int, body: bytes, timeout: float = 5.0) -> bytes:
        await self.client.write_gatt_char(CH_DATA, frame(data_type, body), response=True)
        while True:
            reply = await asyncio.wait_for(self._inbox.get(), timeout)
            if len(reply) >= 5 and reply[2] == FRAME_SINGLE_CMD:
                await self.client.write_gatt_char(CH_IN, bytes.fromhex("00000300"), response=True)
                await asyncio.sleep(0.05)
                return reply

    async def authenticate(self):
        app_random = os.urandom(16)
        verify_payload = pb_bytes(30, pb_bytes(1, app_random))
        reply = await self._request(DT_PROTOBUF, build_pyt(CMD_APP_VERIFY, verify_payload))

        _, _, payload = parse_pyt(reply[4:])
        inner = pb_parse(pb_parse(payload)[31][0][1])
        device_random = inner[1][0][1]
        device_sign = inner[2][0][1]

        derived = HKDF(
            algorithm=hashes.SHA256(),
            length=64,
            salt=app_random + device_random,
            info=b"miwear-auth",
        ).derive(self.device_key)
        keys = SessionKeys(derived[0:16], derived[16:32], derived[32:36], derived[36:40])

        expected = hmac.new(keys.device_key, device_random + app_random, hashlib.sha256).digest()
        if not hmac.compare_digest(expected, device_sign):
            raise RuntimeError("手环签名校验失败：设备密钥不匹配")

        await asyncio.sleep(0.4)  # 等待 ACK 被手环处理
        app_sign = hmac.new(keys.app_key, app_random + device_random, hashlib.sha256).digest()
        # 设备信息 protobuf（字段定义见 defpackage/jd0.java）
        device_info = pb_varint(1, 0) + b"\x15" + struct.pack("<f", 29.0)
        device_info += pb_bytes(3, b"MI 8") + pb_bytes(5, b"CN")
        encrypted_info = AESCCM(keys.app_key, tag_length=4).encrypt(keys.app_iv + b"\x00" * 8, device_info, None)
        confirm_payload = pb_bytes(32, pb_bytes(1, app_sign) + pb_bytes(2, encrypted_info))

        reply = await self._request(DT_PROTOBUF, build_pyt(CMD_APP_CONFIRM, confirm_payload))
        _, _, payload = parse_pyt(reply[4:])
        code = pb_parse(pb_parse(payload)[33][0][1])[1][0][1]
        if code != 1:
            raise RuntimeError(f"认证被拒绝，code={code}")
        self.keys = keys

    async def send_ble(self, command_type: int, subtype: int, payload: bytes = b"",
                       timeout: float = 6.0) -> bytes:
        """发送已认证后的业务 Command，并返回解密后的明文 Command。"""
        if self.keys is None:
            raise RuntimeError("尚未认证")
        command = pb_varint(1, command_type) + pb_varint(2, subtype) + payload
        body = self.keys.encrypt(command)
        reply = await self._request(DT_ENCRYPTED, body, timeout)
        if len(reply) <= 6:
            return b""
        if reply[3] == DT_ENCRYPTED:
            return self.keys.decrypt(reply[4:])
        return reply[4:]

    async def battery(self) -> int | None:
        plain = await self.send_ble(2, 1)
        try:
            command = pb_parse(plain)
            system = pb_parse(command[4][0][1])
            battery = pb_parse(system[2][0][1])
            detail = pb_parse(battery[1][0][1])
            return detail[1][0][1]
        except Exception:
            return None

    async def find_watch(self) -> None:
        await self.send_ble(2, 18)

    async def notify(self, title: str, body: str, app_name: str = "Cindy",
                     package: str = "com.cindy.band", notification_id: int = 1) -> bool:
        import time as _time
        timestamp = _time.strftime("%Y%m%dT%H%M%S", _time.localtime())
        key = "0|%s|%d|null|%d" % (package, notification_id, int(_time.time() * 1000) % 100000)
        n3 = (
            pb_bytes(1, package.encode())
            + pb_bytes(2, app_name.encode())
            + pb_bytes(3, title.encode())
            + pb_bytes(4, b"")
            + pb_bytes(5, body.encode())
            + pb_bytes(6, timestamp.encode())
            + pb_varint(7, notification_id)
            + pb_bytes(12, key.encode())
        )
        notification = pb_bytes(3, pb_bytes(1, n3))
        await self.send_ble(7, 0, pb_bytes(9, notification))
        return True

    async def send_command(self, command: int, payload: bytes = b"", use_encryption: bool = True) -> bytes:
        """发送业务命令。命令号与 payload 结构待补齐（见文档）。"""
        if use_encryption:
            if self.keys is None:
                raise RuntimeError("尚未认证")
            body = self.keys.encrypt(build_pyt(command, payload))
            return await self._request(DT_ENCRYPTED, body)
        return await self._request(DT_PROTOBUF, build_pyt(command, payload))


async def demo(address: str, device_key_hex: str):
    async with Band8Client(address, bytes.fromhex(device_key_hex)) as band:
        print("认证成功，会话密钥已建立")
        print("deviceKey:", band.keys.device_key.hex())
        print("appKey   :", band.keys.app_key.hex())


if __name__ == "__main__":
    import sys

    if len(sys.argv) != 3:
        print("usage: python -m band8.client <MAC> <encrypt_key_hex>")
        raise SystemExit(2)
    asyncio.run(demo(sys.argv[1], sys.argv[2]))


