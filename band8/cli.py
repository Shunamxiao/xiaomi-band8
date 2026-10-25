"""PC 端命令行：扫描、密钥管理、连接、通知。

运行示例：
    python -m band8.cli scan
    python -m band8.cli import AA:BB:CC:DD:EE:FF 0123456789abcdef0123456789abcdef
    python -m band8.cli info AA:BB:CC:DD:EE:FF
    python -m band8.cli notify AA:BB:CC:DD:EE:FF "标题" "正文"
    python -m band8.cli find AA:BB:CC:DD:EE:FF
"""
from __future__ import annotations

import argparse
import asyncio
import sys
import time

from .client import Band8Client
from .keystore import KeyStore


XIAOMI_PREFIXES = ("D0:62:2C", "C8:47:8C", "F8:5C:7D", "A4:C1:38", "7C:9E:BD")
FE95 = "0000fe95-0000-1000-8000-00805f9b34fb"


async def scan(timeout: float = 6.0) -> None:
    from bleak import BleakScanner

    print(f"扫描 BLE（{timeout:.0f} 秒）...")
    found = await BleakScanner.discover(timeout=timeout, return_adv=True)
    rows = []
    for address, (device, adv) in found.items():
        name = device.name or adv.local_name or ""
        uuids = [u.lower() for u in (adv.service_uuids or [])]
        mfg = adv.manufacturer_data or {}
        is_xiaomi = (
            address.upper().startswith(XIAOMI_PREFIXES)
            or FE95 in uuids
            or any(key in (0xFE95, 0x038F, 0x0157) for key in mfg)
            or "band" in name.lower()
            or "mi " in name.lower()
        )
        if is_xiaomi:
            rows.append((address, name, adv.rssi, bool(FE95 in uuids)))
    if not rows:
        print("未发现小米设备。请确认手环未被其他主机连接，并靠近电脑。")
        return
    for address, name, rssi, fe95 in rows:
        mark = "FE95" if fe95 else "----"
        print(f"  [{mark}] {address}  {name or '(no name)'}  rssi={rssi}")


async def info(mac: str) -> None:
    store = KeyStore()
    dev = store.get(mac)
    if dev is None:
        print(f"密钥库中没有 {mac}，请先 import 或 bind。")
        return
    async with Band8Client(dev.mac, dev.key_bytes) as band:
        battery = await band.battery()
        print(f"认证成功: {dev.mac} ({dev.model})")
        print(f"电量: {battery}%" if battery is not None else "电量: 未知")


async def notify(mac: str, title: str, body: str) -> None:
    store = KeyStore()
    dev = store.get(mac)
    if dev is None:
        print(f"密钥库中没有 {mac}，请先 import 或 bind。")
        return
    async with Band8Client(dev.mac, dev.key_bytes) as band:
        await band.notify(title, body)
        print("通知已发送")


async def find(mac: str) -> None:
    store = KeyStore()
    dev = store.get(mac)
    if dev is None:
        print(f"密钥库中没有 {mac}，请先 import 或 bind。")
        return
    async with Band8Client(dev.mac, dev.key_bytes) as band:
        await band.find_watch()
        print("查找指令已发送")


def main() -> None:
    parser = argparse.ArgumentParser(prog="band8")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_scan = sub.add_parser("scan", help="扫描小米 BLE 设备")
    p_scan.add_argument("--timeout", type=float, default=6.0)

    sub.add_parser("list", help="列出密钥库")

    p_import = sub.add_parser("import", help="导入已有密钥")
    p_import.add_argument("mac")
    p_import.add_argument("key")
    p_import.add_argument("--model", default="m66nfc")

    p_info = sub.add_parser("info", help="连接并读取电量")
    p_info.add_argument("mac")

    p_notify = sub.add_parser("notify", help="发送通知")
    p_notify.add_argument("mac")
    p_notify.add_argument("title")
    p_notify.add_argument("body")

    p_find = sub.add_parser("find", help="查找手环")
    p_find.add_argument("mac")

    p_bind = sub.add_parser("bind", help="本地绑定（实验性，需未绑定手环）")
    p_bind.add_argument("mac")
    p_bind.add_argument("--model", default="m66nfc")

    args = parser.parse_args()

    if args.cmd == "scan":
        asyncio.run(scan(args.timeout))
    elif args.cmd == "list":
        items = KeyStore().all()
        if not items:
            print("密钥库为空")
        for dev in items:
            when = time.strftime("%Y-%m-%d %H:%M", time.localtime(dev.bound_at)) if dev.bound_at else "-"
            print(f"{dev.mac}  {dev.model}  key={dev.encrypt_key[:8]}…  bound={when}")
    elif args.cmd == "import":
        dev = KeyStore().put(args.mac, args.key, model=args.model, bound_at=int(time.time()))
        print(f"已保存 {dev.mac}")
    elif args.cmd == "info":
        asyncio.run(info(args.mac))
    elif args.cmd == "notify":
        asyncio.run(notify(args.mac, args.title, args.body))
    elif args.cmd == "find":
        asyncio.run(find(args.mac))
    elif args.cmd == "bind":
        from .bind import bind_device
        asyncio.run(bind_device(args.mac, args.model))
    else:
        parser.print_help()
        sys.exit(2)


if __name__ == "__main__":
    main()