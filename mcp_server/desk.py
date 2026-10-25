"""
桌搭守护进程：周期性把电脑信息渲染成手环通知卡片。

用法:
    pip install psutil   # 可选，用于 CPU/内存
    python mcp_server/desk.py --interval 10

说明:
    手环没有常亮 API，通知会亮屏数秒后自动熄灭；
    本脚本用固定间隔重复推送来近似“常驻小摆件”。

配置见 desk_config.json（自动生成）。
"""

from __future__ import annotations

import argparse
import json
import os
import socket
import time
import urllib.request
from datetime import datetime

try:
    import psutil
except Exception:
    psutil = None

BASE = os.environ.get("CINDY_BAND_SERVER", "http://127.0.0.1:8801").rstrip("/")
CONFIG_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "desk_config.json")

DEFAULT_CONFIG = {
    "title": "桌搭",
    "interval": 10,
    "show_clock": True,
    "show_cpu": True,
    "show_weather": False,
    "weather_city": "",
    "extra_lines": [],
    "max_body": 160,
}


def load_config() -> dict:
    if not os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH, "w", encoding="utf-8") as fh:
            json.dump(DEFAULT_CONFIG, fh, ensure_ascii=False, indent=2)
        return dict(DEFAULT_CONFIG)
    with open(CONFIG_PATH, encoding="utf-8") as fh:
        config = dict(DEFAULT_CONFIG)
        config.update(json.load(fh))
        return config


def weather_line(city: str) -> str | None:
    if not city:
        return None
    try:
        url = "https://wttr.in/" + urllib.parse.quote(city) + "?format=%t+%C"
        with urllib.request.urlopen(url, timeout=4) as resp:
            return "天气 " + resp.read().decode("utf-8").strip()
    except Exception:
        return None


def build_lines(config: dict) -> list[str]:
    now = datetime.now()
    lines: list[str] = []
    if config["show_clock"]:
        weekday = "一二三四五六日"[now.weekday()]
        lines.append(now.strftime("%H:%M:%S") + "  周" + weekday)
    if config["show_cpu"] and psutil is not None:
        lines.append(f"CPU {psutil.cpu_percent(interval=None):.0f}%  内存 {psutil.virtual_memory().percent:.0f}%")
    if config["show_weather"]:
        line = weather_line(config["weather_city"])
        if line:
            lines.append(line)
    lines.extend(config.get("extra_lines", []))
    lines.append(socket.gethostname())
    return lines


def desk_enabled() -> bool:
    try:
        with urllib.request.urlopen(BASE + "/api/v1/desk", timeout=4) as resp:
            return bool(json.loads(resp.read().decode("utf-8")).get("enabled", False))
    except Exception:
        return True


def push(lines: list[str], title: str, max_body: int) -> None:
    body = "\n".join(lines)
    if len(body) > max_body:
        body = body[: max_body - 3] + "..."
    payload = json.dumps({"title": title, "body": body, "app": "Desk"}, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        BASE + "/api/v1/notify", data=payload, headers={"Content-Type": "application/json"}, method="POST"
    )
    with urllib.request.urlopen(request, timeout=8) as resp:
        resp.read()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--interval", type=int, default=None)
    parser.add_argument("--once", action="store_true")
    args = parser.parse_args()

    config = load_config()
    interval = args.interval or config["interval"]
    print(f"桌搭已启动：每 {interval}s 推送一次，服务端 {BASE}")

    try:
        while True:
            if not desk_enabled():
                print("桌搭已在服务端停止")
                break
            lines = build_lines(config)
            try:
                push(lines, config["title"], config.get("max_body", 160))
                print("推送:", " | ".join(lines))
            except Exception as exc:
                print("推送失败:", exc)
            if args.once:
                break
            time.sleep(interval)
    except KeyboardInterrupt:
        print("已停止")


if __name__ == "__main__":
    main()

