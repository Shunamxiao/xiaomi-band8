# Cindy Band

[English](README.en.md) | 简体中文

![CI](https://github.com/Shunamxiao/xiaomi-band8/actions/workflows/ci.yml/badge.svg)
![License](https://img.shields.io/badge/license-MIT-blue)
![Python](https://img.shields.io/badge/python-3.10%2B-3776AB)
![Android](https://img.shields.io/badge/android-8.0%2B-3DDC84)

自建的小米手环 8（NFC）BLE 客户端。提供 PC 直连、Android App、本地服务端 与 MCP 工具 四种形态，
不依赖官方 App 常驻，支持通知转发、电量查询、设备设置与桌搭展示。

> 仅用于自有设备的研究与自用。与小米公司无任何关联。

## 特性

- 双端实现：PC（Python + Bleak）与 Android（Kotlin + BouncyCastle）
- 完整协议栈：FE95 GATT、帧编解码、HKDF 会话派生、AES-CCM 加密
- 本地绑定：P-256 ECDH 派生 encrypt_key，不需要小米账号（实验性）
- 通知转发：按 App 过滤、可配置是否震动、通知去重
- 设备能力：电量、查找手环、免打扰、手机静音、音乐卡片、床头钟/桌搭
- 服务端：FastAPI + SQLite + Ant Design 风格控制台
- MCP：把手环能力暴露为标准 MCP 工具，供 Claude / Cursor / Codex 调用

## 架构

    ┌─────────────────────────────┐
    │ Android App (Kotlin)        │  通知监听 → 手环
    │  ├─ BandClient  协议栈      │
    │  └─ BandService 前台服务    │
    └──────────────┬──────────────┘
                   │ BLE (FE95)
            ┌──────▼──────┐
            │  Mi Band 8  │
            └──────▲──────┘
                   │ BLE (FE95)
    ┌──────────────┴──────────────┐
    │ PC 客户端 (Python + Bleak)  │
    │  └─ band8/cli.py            │
    └──────────────┬──────────────┘
                   │ HTTP
    ┌──────────────▼──────────────┐
    │ 服务端 (FastAPI + SQLite)    │
    │  └─ Web 控制台 / REST        │
    └──────────────┬──────────────┘
                   │ stdio
    ┌──────────────▼──────────────┐
    │ MCP Server (FastMCP)        │
    └─────────────────────────────┘

## 仓库结构

    android/          Android App（Kotlin，协议栈 + 前台服务 + 通知监听）
    band8/            PC 端 Python 客户端（协议、密钥库、命令行、本地绑定）
    server/           FastAPI 服务端 + 单页控制台
    mcp_server/       MCP 工具与桌搭守护进程
    docs/             协议分析、链路梳理、能力清单、UI 设计
    scripts/          辅助脚本（BLE 扫描、表盘抓包）

## 快速开始

### PC 客户端

    python -m pip install bleak cryptography
    python -m band8.cli scan

拿到手环 MAC 与密钥后：

    python -m band8.cli import AA:BB:CC:DD:EE:FF 0123456789abcdef0123456789abcdef
    python -m band8.cli info AA:BB:CC:DD:EE:FF
    python -m band8.cli notify AA:BB:CC:DD:EE:FF "标题" "正文"

### 密钥从哪里来

encrypt_key 是绑定阶段派生的 16 字节长期密钥，不是固定值。

- 未绑定手环：使用实验性的本地绑定

      python -m band8.cli bind AA:BB:CC:DD:EE:FF

  协议来自官方实现：P-256 ECDH → HKDF(shared, appRandom||devRandom, "miwear-bind")，
  取 derived[40:56] 作为 encrypt_key。当前字段顺序仍需在未绑定设备上验证。

- 已绑定手环：需要先从原绑定方解绑或恢复出厂，再执行本地绑定。
  已绑定设备无法被第三方直接接管，这是设备的安全设计。

### Android App

1. 用 Android Studio 打开 android/；
2. 构建并安装；
3. 在首页填写手环 MAC、设备密钥、服务端地址；
4. 授予通知读取权限与蓝牙权限。

### 服务端

    python -m pip install -r server/requirements.txt
    python server/app.py

默认监听 0.0.0.0:8801，浏览器打开 http://127.0.0.1:8801/ 。

### MCP

    python -m pip install "mcp>=1.9"
    CINDY_BAND_SERVER=http://127.0.0.1:8801 python mcp_server/server.py

在 MCP 客户端中注册该 stdio 服务即可调用手环能力。

## 已验证能力

| 能力 | 状态 | 说明 |
|------|------|------|
| 认证与加密通道 | 已验证 | cmd26/27 + HKDF + AES-CCM |
| 电量 | 已验证 | 2/1 |
| 通知 | 已验证 | 7/0，必须携带 key 字段 |
| 查找手环 | 已验证 | 2/18 |
| 免打扰 / 手机静音 | 已验证 | 2/23 / 2/44 |
| 音乐卡片 | 已验证 | 18/1 |
| 床头钟 / 桌搭 | 已验证 | 17/9（官方 bedtime_clock） |
| 自定义表盘 | 研究中 | 需要表盘包与签名 |
| 本地绑定 | 已还原协议 | 待未绑定设备验证 |

## 界面

Android App：

![Android App](docs/app-ui-v2.png)

服务端控制台：

![Web Console](docs/console-ui.png)

## 安全与隐私

- 设备密钥只保存在本机（band8/keystore.json，已加入 .gitignore）；
- 服务端不收集任何遥测数据；
- 不要把 encrypt_key、设备 MAC、内网地址提交到公开仓库；
- 详见 SECURITY.md。

## 免责声明

本项目为个人研究与自用工具，不提供任何形式的担保。

- 不用于接管他人设备；
- 不绕过付费内容、账号权益或设备授权；
- 逆向实现可能随固件/官方 App 更新失效；
- 使用本项目的风险由使用者自行承担。

## License

MIT（见 LICENSE）
