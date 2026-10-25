"""
Cindy Band 自建服务端

- 为 APK 提供配置、事件上报、命令下发
- 自带单页控制台（内联 HTML/CSS/JS，无外部依赖）
- 监听 ::8801（同时支持 IPv4/IPv6）

启动:
    pip install -r server/requirements.txt
    python server/app.py
"""

from __future__ import annotations

import json
import sqlite3
import time
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse
from pydantic import BaseModel

BASE_DIR = Path(__file__).resolve().parent
DB_PATH = BASE_DIR / "band.db"

app = FastAPI(title="Cindy Band Server", version="0.2.0")

DEFAULT_CONFIG = {
    "enabled": True,
    "blocked_packages": [
        "com.android.systemui",
        "com.miui.systemui",
        "com.mi.health",
        "com.cindy.band",
    ],
    "app_name": "Cindy",
    "desk_enabled": False,
    "notify_vibrate": True,
}


def db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init_db() -> None:
    with db() as conn:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL,
                package TEXT,
                title TEXT,
                body TEXT,
                ts INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS commands (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                body TEXT NOT NULL DEFAULT '',
                app TEXT NOT NULL DEFAULT 'Cindy',
                type INTEGER,
                subtype INTEGER,
                payload TEXT,
                status TEXT NOT NULL DEFAULT 'pending',
                created INTEGER NOT NULL,
                delivered INTEGER
            );
            CREATE TABLE IF NOT EXISTS settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            """
        )
        conn.execute(
            "INSERT OR IGNORE INTO settings(key, value) VALUES ('config', ?)",
            (json.dumps(DEFAULT_CONFIG, ensure_ascii=False),),
        )


def ensure_columns() -> None:
    with db() as conn:
        cols = {row["name"] for row in conn.execute("PRAGMA table_info(commands)")}
        for name, decl in (
            ("type", "INTEGER"),
            ("subtype", "INTEGER"),
            ("payload", "TEXT"),
            ("vibrate", "INTEGER"),
        ):
            if name not in cols:
                conn.execute(f"ALTER TABLE commands ADD COLUMN {name} {decl}")


init_db()
ensure_columns()


class EventIn(BaseModel):
    type: str = "notification"
    package: str | None = None
    title: str | None = None
    body: str | None = None
    ts: int | None = None


class CommandIn(BaseModel):
    title: str = ""
    body: str = ""
    app: str = "Cindy"
    type: int | None = None
    subtype: int | None = None
    payload: str | None = None
    # None 表示跟随全局配置；True/False 覆盖单条通知
    vibrate: bool | None = None


class ConfigIn(BaseModel):
    enabled: bool = True
    # None 表示本次不改动屏蔽列表（App 只同步开关时使用）
    blocked_packages: list[str] | None = None
    app_name: str = "Cindy"
    notify_vibrate: bool = True


def load_config() -> dict:
    with db() as conn:
        row = conn.execute("SELECT value FROM settings WHERE key='config'").fetchone()
    stored = json.loads(row["value"]) if row else {}
    return {**DEFAULT_CONFIG, **stored}


@app.get("/api/v1/health")
def health():
    with db() as conn:
        events = conn.execute("SELECT COUNT(*) AS n FROM events").fetchone()["n"]
        pending = conn.execute(
            "SELECT COUNT(*) AS n FROM commands WHERE status='pending'"
        ).fetchone()["n"]
    return {"ok": True, "events": events, "pending_commands": pending, "ts": int(time.time())}


@app.get("/api/v1/config")
def get_config():
    return JSONResponse(load_config())


@app.post("/api/v1/config")
def set_config(cfg: ConfigIn):
    data = cfg.model_dump()
    if data.get("blocked_packages") is None:
        data["blocked_packages"] = load_config().get("blocked_packages", [])
    value = json.dumps(data, ensure_ascii=False)
    with db() as conn:
        conn.execute(
            "INSERT INTO settings(key, value) VALUES('config', ?) "
            "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            (value,),
        )
    return {"ok": True, "config": data}


@app.post("/api/v1/events")
async def post_event(request: Request):
    payload = await request.json()
    event = EventIn(**payload)
    with db() as conn:
        conn.execute(
            "INSERT INTO events(type, package, title, body, ts) VALUES (?, ?, ?, ?, ?)",
            (event.type, event.package, event.title, event.body, event.ts or int(time.time() * 1000)),
        )
    return {"ok": True}


@app.get("/api/v1/events")
def list_events(limit: int = 50):
    with db() as conn:
        rows = conn.execute(
            "SELECT * FROM events ORDER BY id DESC LIMIT ?", (min(limit, 500),)
        ).fetchall()
    return {"items": [dict(row) for row in rows]}


def enqueue(cmd: CommandIn) -> int:
    now = int(time.time() * 1000)
    vibrate = cmd.vibrate
    if vibrate is None:
        vibrate = bool(load_config().get("notify_vibrate", True))
    with db() as conn:
        cur = conn.execute(
            "INSERT INTO commands(title, body, app, type, subtype, payload, vibrate, status, created) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, 'pending', ?)",
            (cmd.title, cmd.body, cmd.app, cmd.type, cmd.subtype, cmd.payload,
             1 if vibrate else 0, now),
        )
        return cur.lastrowid


@app.post("/api/v1/notify")
def push_notification(cmd: CommandIn):
    if cmd.vibrate is None:
        cmd.vibrate = bool(load_config().get("notify_vibrate", True))
    return {"ok": True, "id": enqueue(cmd), "vibrate": cmd.vibrate}


@app.post("/api/v1/command")
def push_command(cmd: CommandIn):
    if cmd.type is None:
        raise HTTPException(400, "type required")
    return {"ok": True, "id": enqueue(cmd)}


@app.post("/api/v1/find")
def find_watch():
    return {"ok": True, "id": enqueue(CommandIn(type=2, subtype=18))}


@app.post("/api/v1/dnd")
def set_dnd(enabled: bool = True):
    # System.dndStatus = field 11 -> DoNotDisturb.status: 0 enabled, 2 disabled
    payload = bytes([0x5A, 0x02, 0x08, 0x00 if enabled else 0x02])
    return {"ok": True, "id": enqueue(CommandIn(type=2, subtype=23, payload=payload.hex()))}


@app.post("/api/v1/silent")
def set_silent(enabled: bool = True):
    # System.phoneSilentModeSet = field 37 -> PhoneSilentMode.silent = 1
    payload = bytes([0xAA, 0x02, 0x02, 0x08, 0x01 if enabled else 0x00])
    return {"ok": True, "id": enqueue(CommandIn(type=2, subtype=44, payload=payload.hex()))}


@app.post("/api/v1/music")
def push_music(state: int = 1, track: str = "", artist: str = "", volume: int = 50):
    def varint(n: int) -> bytes:
        out = b""
        while True:
            part = n & 0x7F
            n >>= 7
            out += bytes([part | 0x80]) if n else bytes([part])
            if not n:
                return out

    def fld_int(no: int, v: int) -> bytes:
        return varint(no << 3) + varint(v)

    def fld_bytes(no: int, data: bytes) -> bytes:
        return varint((no << 3) | 2) + varint(len(data)) + data

    info = fld_int(1, state) + fld_int(2, volume) + fld_bytes(4, track.encode()) + fld_bytes(5, artist.encode())
    payload = fld_bytes(1, info)
    return {"ok": True, "id": enqueue(CommandIn(type=18, subtype=1, payload=payload.hex()))}


def desk_state() -> bool:
    cfg = load_config()
    return bool(cfg.get("desk_enabled", False))


def set_desk_state(enabled: bool) -> None:
    cfg = load_config()
    cfg["desk_enabled"] = enabled
    with db() as conn:
        conn.execute(
            "INSERT INTO settings(key, value) VALUES('config', ?) "
            "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            (json.dumps(cfg, ensure_ascii=False),),
        )


@app.get("/api/v1/desk")
def get_desk():
    return {"enabled": desk_state()}


@app.post("/api/v1/desk/start")
def start_desk():
    set_desk_state(True)
    return {"ok": True, "enabled": True}


@app.post("/api/v1/desk/stop")
def stop_desk():
    set_desk_state(False)
    return {"ok": True, "enabled": False}


@app.post("/api/v1/screen-on")
def screen_on(enabled: bool = True):
    # Notification.screenOnOnNotifications = field 7
    payload = bytes([0x38, 0x01 if enabled else 0x00])
    return {"ok": True, "id": enqueue(CommandIn(type=7, subtype=7, payload=payload.hex()))}


def _varint(n: int) -> bytes:
    out = b""
    while True:
        part = n & 0x7F
        n >>= 7
        out += bytes([part | 0x80]) if n else bytes([part])
        if not n:
            return out


def _fld_int(no: int, v: int) -> bytes:
    return _varint(no << 3) + _varint(v)


def _fld_bytes(no: int, data: bytes) -> bytes:
    return _varint((no << 3) | 2) + _varint(len(data)) + data


@app.get("/api/v1/bedtime")
def get_bedtime():
    return {"ok": True, "id": enqueue(CommandIn(type=17, subtype=8))}


@app.post("/api/v1/bedtime")
def set_bedtime(
    enabled: bool = True,
    sleep_hour: int = 23,
    sleep_minute: int = 0,
    wake_hour: int = 7,
    wake_minute: int = 0,
):
    """床头钟/睡眠模式（bin 17/9）。开启后充电时显示横屏时钟。"""
    state = 1 if enabled else 0
    jn4_sleep = _fld_int(1, sleep_hour) + _fld_int(2, sleep_minute)
    jn4_wake = _fld_int(1, wake_hour) + _fld_int(2, wake_minute)
    a = _fld_bytes(1, jn4_sleep) + _fld_bytes(2, jn4_wake)
    qh4 = _fld_int(1, state) + _fld_bytes(2, a)
    kh4 = _fld_bytes(9, qh4)
    return {
        "ok": True,
        "id": enqueue(CommandIn(type=17, subtype=9, payload=kh4.hex())),
        "payload": kh4.hex(),
    }


@app.get("/api/v1/results")
def command_results(limit: int = 20):
    with db() as conn:
        rows = conn.execute(
            "SELECT * FROM events WHERE type='command_result' ORDER BY id DESC LIMIT ?",
            (min(limit, 200),),
        ).fetchall()
    return {"items": [dict(row) for row in rows]}


@app.get("/api/v1/commands")
def take_commands():
    with db() as conn:
        rows = conn.execute(
            "SELECT * FROM commands WHERE status='pending' ORDER BY id ASC LIMIT 20"
        ).fetchall()
        if rows:
            ids = [row["id"] for row in rows]
            conn.execute(
                "UPDATE commands SET status='delivered', delivered=? WHERE id IN (%s)"
                % ",".join("?" for _ in ids),
                (int(time.time() * 1000), *ids),
            )
    items = []
    for row in rows:
        item = dict(row)
        if item.get("vibrate") is not None:
            item["vibrate"] = bool(item["vibrate"])
        items.append(item)
    return {"items": items}


@app.get("/", response_class=HTMLResponse)
def console():
    return HTMLResponse(CONSOLE_HTML)


CONSOLE_HTML = (BASE_DIR / "console.html").read_text(encoding="utf-8")


if __name__ == "__main__":
    import uvicorn

    import os
    host = os.environ.get("CINDY_BAND_HOST", "0.0.0.0")
    port = int(os.environ.get("CINDY_BAND_PORT", "8801"))
    uvicorn.run(app, host=host, port=port)

