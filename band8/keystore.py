"""小米手环 8 本地密钥库。

每台手环一条记录：MAC -> encrypt_key（绑定阶段派生）。
密钥只保存在本机，不要提交到仓库。
"""
from __future__ import annotations

import json
import os
from dataclasses import dataclass, asdict
from pathlib import Path


DEFAULT_PATH = Path(__file__).resolve().parent / "keystore.json"


@dataclass
class DeviceKey:
    mac: str
    model: str = "m66nfc"
    encrypt_key: str = ""
    app_device_id: str = ""
    bound_at: int = 0

    @property
    def key_bytes(self) -> bytes:
        return bytes.fromhex(self.encrypt_key)


class KeyStore:
    def __init__(self, path: Path = DEFAULT_PATH):
        self.path = Path(path)
        self._items: dict[str, DeviceKey] = {}
        self.load()

    def load(self) -> None:
        if not self.path.exists():
            return
        data = json.loads(self.path.read_text(encoding="utf-8"))
        for item in data.get("devices", []):
            try:
                dev = DeviceKey(**item)
            except TypeError:
                continue
            self._items[dev.mac.upper()] = dev

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = {"devices": [asdict(v) for v in self._items.values()]}
        self.path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        try:
            os.chmod(self.path, 0o600)
        except OSError:
            pass

    def get(self, mac: str) -> DeviceKey | None:
        return self._items.get(mac.upper())

    def put(self, mac: str, encrypt_key: str, model: str = "m66nfc",
            app_device_id: str = "", bound_at: int = 0) -> DeviceKey:
        dev = DeviceKey(
            mac=mac.upper(),
            model=model,
            encrypt_key=encrypt_key.lower(),
            app_device_id=app_device_id,
            bound_at=bound_at,
        )
        self._items[dev.mac] = dev
        self.save()
        return dev

    def remove(self, mac: str) -> bool:
        removed = self._items.pop(mac.upper(), None) is not None
        if removed:
            self.save()
        return removed

    def all(self) -> list[DeviceKey]:
        return list(self._items.values())