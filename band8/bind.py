"""本地绑定（实验性）。

协议来自 com.xiaomi.device.binder.LocalWearBinderV2：
  type=1 subtype=18  bind hello   ua0{17: sb0{1: appDeviceId, 2: appPublicKey}}
  type=1 subtype=19  bind confirm ua0{19: pb0{1: appRandom, 2: sign}}
  type=1 subtype=25  bind result  ua0{29: uc0}（可选）

绑定完成后 encrypt_key = HKDF(sharedKey, appRandom||devRandom, "miwear-bind")[40:56]。

警告：绑定会改写手环的归属密钥，只能在未绑定/已解绑的手环上使用。
"""
from __future__ import annotations

import asyncio
import hashlib
import hmac
import os
import struct
import time

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

from .client import (
    CH_DATA,
    CH_IN,
    DT_PROTOBUF,
    FRAME_SINGLE_CMD,
    pb_bytes,
    pb_parse,
    pb_varint,
)
from .keystore import KeyStore


def command(ctype: int, subtype: int, payload: bytes = b"") -> bytes:
    return pb_varint(1, ctype) + pb_varint(2, subtype) + pb_bytes(3, payload)


def ua0(field_no: int, body: bytes) -> bytes:
    return pb_bytes(field_no, body)


class BindClient:
    def __init__(self, address: str):
        self.address = address
        self.client = None
        self._queue: asyncio.Queue = asyncio.Queue()

    async def __aenter__(self):
        from bleak import BleakClient

        self.client = BleakClient(self.address, timeout=20)
        await self.client.connect()
        await self.client.start_notify(CH_IN, self._on_notify)
        await asyncio.sleep(0.3)
        return self

    async def __aexit__(self, *exc):
        if self.client:
            try:
                await self.client.stop_notify(CH_IN)
            except Exception:
                pass
            await self.client.disconnect()

    async def _on_notify(self, _char, data: bytearray):
        await self._queue.put(bytes(data))

    async def request(self, payload: bytes, timeout: float = 8.0) -> bytes:
        frame = struct.pack("<HBB", 0, FRAME_SINGLE_CMD, DT_PROTOBUF) + payload
        await self.client.write_gatt_char(CH_DATA, frame, response=True)
        while True:
            reply = await asyncio.wait_for(self._queue.get(), timeout)
            if len(reply) >= 4 and reply[2] == FRAME_SINGLE_CMD:
                await self.client.write_gatt_char(CH_IN, bytes.fromhex("00000300"), response=True)
                await asyncio.sleep(0.05)
                return reply[4:]


def app_device_id(mac: str, phone_id: str = "", user_id: str = "") -> str:
    raw = f"{phone_id}{mac.upper()}{user_id}".encode()
    return hashlib.md5(raw).hexdigest()


def _split_yb(payload: bytes) -> tuple[bytes, bytes, bytes]:
    """按长度识别 yb0 中的公钥/签名/随机数，避免依赖字段顺序。"""
    fields = pb_parse(payload)
    values = [v for _, v in fields.get(18, []) if isinstance(v, bytes)]
    if not values:
        raise RuntimeError("绑定响应缺少字段 18")
    inner = pb_parse(values[0])
    items = [v for _, v in inner.items() for _, v in v if isinstance(v, bytes)]
    pub = next((x for x in items if len(x) == 64), None)
    sign = next((x for x in items if len(x) == 32), None)
    random = next((x for x in items if len(x) == 16), None)
    if not (pub and sign and random):
        raise RuntimeError(f"无法识别绑定响应字段: {[len(x) for x in items]}")
    return pub, sign, random


async def bind_device(mac: str, model: str = "m66nfc") -> None:
    print("警告：本地绑定会改写手环密钥，只能用于未绑定/已解绑的手环。")
    print("      如果这是正在使用的手环，请立即停止（Ctrl+C）。")
    await asyncio.sleep(3)

    private_key = ec.generate_private_key(ec.SECP256R1())
    numbers = private_key.public_key().public_numbers()
    public_key = numbers.x.to_bytes(32, "big") + numbers.y.to_bytes(32, "big")
    device_id = app_device_id(mac)

    async with BindClient(mac) as band:
        hello = ua0(17, pb_bytes(1, device_id.encode()) + pb_bytes(2, public_key))
        reply = await band.request(command(1, 18, hello))
        pub, sign, device_random = _split_yb(reply)

        peer_numbers = ec.EllipticCurvePublicNumbers(
            int.from_bytes(pub[:32], "big"),
            int.from_bytes(pub[32:], "big"),
            ec.SECP256R1(),
        )
        shared = private_key.exchange(ec.ECDH(), peer_numbers.public_key())

        expected = hmac.new(shared, device_random, hashlib.sha256).digest()
        if not hmac.compare_digest(expected, sign):
            raise RuntimeError("手环签名校验失败：绑定密钥协商异常")

        app_random = os.urandom(16)
        derived = HKDF(
            algorithm=hashes.SHA256(),
            length=64,
            salt=app_random + device_random,
            info=b"miwear-bind",
        ).derive(shared)
        encrypt_key = derived[40:56]

        confirm_sign = hmac.new(shared, app_random, hashlib.sha256).digest()
        confirm = ua0(19, pb_bytes(1, app_random) + pb_bytes(2, confirm_sign))
        await band.request(command(1, 19, confirm))

    store = KeyStore()
    dev = store.put(
        mac,
        encrypt_key.hex(),
        model=model,
        app_device_id=device_id,
        bound_at=int(time.time()),
    )
    print(f"绑定完成，已保存密钥：{dev.mac}  key={dev.encrypt_key}")
    print("下一步：python -m band8.cli info " + dev.mac)