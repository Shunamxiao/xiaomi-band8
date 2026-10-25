# Cindy Band MCP 服务

把手环能力封装成 MCP 工具，任何支持 MCP 的客户端（Claude Desktop、Cursor、Codex 等）都能调用。

## 架构

```
MCP 客户端 ──stdio──> mcp_server/server.py ──HTTP──> server/app.py ──轮询──> APK ──BLE──> 手环
```

APK 每 5 秒拉取一次命令，端到端延迟通常 1~6 秒。

## 安装

```bash
pip install mcp psutil
```

## 启动

```bash
# 先启动自建服务
python server/app.py

# MCP 服务（stdio，供客户端拉起）
CINDY_BAND_SERVER=http://127.0.0.1:8801 python mcp_server/server.py
```

## 客户端配置示例

Claude Desktop / Cursor：

```json
{
  "mcpServers": {
    "cindy-band": {
      "command": "python",
      "args": ["D:/xiaomi-watch/mcp_server/server.py"],
      "env": {
        "CINDY_BAND_SERVER": "http://127.0.0.1:8801"
      }
    }
  }
}
```

## 工具列表

| 工具 | 说明 |
|------|------|
| `band_status` | 服务与链路状态 |
| `band_notify(title, body, app)` | 推送任意通知 |
| `band_desk_card(lines, title)` | 多行文本渲染成桌搭卡片 |
| `band_find` | 查找手环（震动） |
| `band_dnd(enabled)` | 免打扰 |
| `band_silent(enabled)` | 手机静音同步 |
| `band_music(state, track, artist, volume)` | 音乐卡片 |
| `band_command(type, subtype, payload_hex)` | 任意协议命令 |
| `band_results(limit)` | 读取回包与异步事件 |

## 桌搭守护进程

```bash
python mcp_server/desk.py --interval 10
python mcp_server/desk.py --once     # 只推一次
```

首次运行生成 `desk_config.json`：

```json
{
  "title": "桌搭",
  "interval": 10,
  "show_clock": true,
  "show_cpu": true,
  "show_weather": false,
  "weather_city": "",
  "extra_lines": [],
  "max_body": 160
}
```

卡片内容示例：

```
23:26:54  周六
CPU 33%  内存 60%
your-pc
```

## 能力边界（务必了解）

- 手环 8 **没有第三方 UI 运行时**，不能把手写界面渲染到腕上
- 也**没有屏幕常亮接口**，通知亮屏数秒后自动熄灭
- 因此“桌搭”采用：**周期性推送文本卡片** + 屏幕随通知亮起，近似常驻展示
- 更接近“摆件”的方案：自定义表盘作底 + 上面这套数据卡片刷新
- 真正自定义常驻界面需走小米官方小程序（签名+审核）或改固件，均不建议

## 常见问题

- 推送成功但手环不显示：确认通知 payload 带 `key` 字段（已内置）
- 延迟高：检查 APK 是否在运行（`ps -A | grep cindy.band`）
- 连接失败：确认「小米运动健康」已停用，手环未被其它主机占用

