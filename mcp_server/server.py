"""
Cindy Band MCP 服务

把手环能力暴露成 MCP 工具，供任意 MCP 客户端（Claude Desktop、Cursor、Codex 等）调用。
底层通过自建 HTTP 服务（server/app.py）中转，APK 每 5 秒拉取命令并执行。

启动（stdio）:
    python mcp_server/server.py

环境变量:
    CINDY_BAND_SERVER   默认 http://127.0.0.1:8801
"""

from __future__ import annotations

import json
import os
import time
import urllib.parse
import urllib.request

from mcp.server.fastmcp import FastMCP

BASE = os.environ.get("CINDY_BAND_SERVER", "http://127.0.0.1:8801").rstrip("/")
mcp = FastMCP("cindy-band")


def _get(path: str) -> dict:
    with urllib.request.urlopen(BASE + path, timeout=8) as resp:
        return json.loads(resp.read().decode("utf-8"))


def _post(path: str, payload: dict | None = None) -> dict:
    data = json.dumps(payload or {}).encode("utf-8")
    request = urllib.request.Request(
        BASE + path, data=data, headers={"Content-Type": "application/json"}, method="POST"
    )
    with urllib.request.urlopen(request, timeout=8) as resp:
        return json.loads(resp.read().decode("utf-8"))


@mcp.tool()
def band_status() -> str:
    """查看服务端与手环链路状态（事件数、待下发命令数）。"""
    return json.dumps(_get("/api/v1/health"), ensure_ascii=False)


@mcp.tool()
def band_notify(title: str, body: str, app: str = "Cindy") -> str:
    """向手环推送一条通知（任意标题与正文）。"""
    return json.dumps(_post("/api/v1/notify", {"title": title, "body": body, "app": app}), ensure_ascii=False)


@mcp.tool()
def band_desk_card(lines: list[str], title: str = "桌搭") -> str:
    """渲染一张桌搭卡片：多行文本拼成一条通知，手环亮屏展示。"""
    body = "\n".join(lines)
    if len(body) > 160:
        body = body[:157] + "..."
    return json.dumps(_post("/api/v1/notify", {"title": title, "body": body, "app": "Desk"}), ensure_ascii=False)


@mcp.tool()
def band_find() -> str:
    """让手环震动（查找手环）。"""
    return json.dumps(_post("/api/v1/find"), ensure_ascii=False)


@mcp.tool()
def band_dnd(enabled: bool = True) -> str:
    """开关手环免打扰。"""
    return json.dumps(_post("/api/v1/dnd?enabled=" + ("true" if enabled else "false")), ensure_ascii=False)


@mcp.tool()
def band_silent(enabled: bool = True) -> str:
    """开关手机静音同步到手环。"""
    return json.dumps(_post("/api/v1/silent?enabled=" + ("true" if enabled else "false")), ensure_ascii=False)


@mcp.tool()
def band_music(state: int = 1, track: str = "", artist: str = "", volume: int = 50) -> str:
    """把当前播放信息推送到手环音乐卡片（state: 0 停止 / 1 播放 / 2 暂停）。"""
    query = urllib.parse.urlencode({"state": state, "track": track, "artist": artist, "volume": volume})
    return json.dumps(_post("/api/v1/music?" + query), ensure_ascii=False)


@mcp.tool()
def band_command(type: int, subtype: int, payload_hex: str = "") -> str:
    """发送任意 Xiaomi 协议命令（高级用法，见 docs/capabilities.md）。"""
    return json.dumps(
        _post("/api/v1/command", {"type": type, "subtype": subtype, "payload": payload_hex}),
        ensure_ascii=False,
    )


@mcp.tool()
def band_results(limit: int = 10) -> str:
    """读取手环最近的回包（命令结果、异步事件）。"""
    return json.dumps(_get(f"/api/v1/results?limit={limit}"), ensure_ascii=False)


if __name__ == "__main__":
    mcp.run()

