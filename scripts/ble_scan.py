
import asyncio
from bleak import BleakScanner

async def main():
    print("扫描 BLE 设备 (5秒)...")
    found = await BleakScanner.discover(timeout=5, return_adv=True)
    for addr, (dev, adv) in found.items():
        name = dev.name or adv.local_name or "(unknown)"
        rssi = adv.rssi
        uuids = adv.service_uuids
        mfg = adv.manufacturer_data
        hit = "Xiaomi" in name or "Smart Band" in name or addr.upper().startswith("D0:62:2C") or 0xFE95 in (mfg or {})
        mark = "[HIT] " if hit else "      "
        line = f"{mark}{addr} {name} rssi={rssi}"
        if uuids: line += f" svcs={uuids[:3]}"
        if mfg: line += f" mfg={ {hex(k): (v.hex()[:24]) for k,v in list(mfg.items())[:2]} }"
        print(line)

asyncio.run(main())

