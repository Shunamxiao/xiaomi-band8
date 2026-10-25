# v0.2.0 — Signed release

## 重点

- 使用独立 release keystore 正式签名（可长期升级）
- App 支持在界面配置手环 MAC 与设备密钥，不再硬编码
- 新增英文 README（README.en.md）
- 构建支持 keystore.properties，密钥不会进入仓库

## 签名信息

- 证书 DN：CN=Cindy Band, OU=Research, O=Cindy Band, L=Shanghai, ST=Shanghai, C=CN
- SHA-256：f73264b099ea10794e73fa942036c789b03d2c9f332a6d091dc43b575916a777

## 安装说明

v0.1.0 的 APK 是 debug 签名，与本版本签名不同。
从旧版本升级需要先卸载旧 App（会保留手环本身的绑定，不影响设备）。

## 功能

- PC 直连 / Android App / 服务端 / MCP
- 通知转发（可选震动）、电量、查找、免打扰、静音、音乐卡片、床头钟
- 本地绑定协议（实验性，需要未绑定手环）

## 已知限制

- 本地绑定字段顺序待未绑定设备验证
- 已绑定手环无法被第三方直接接管
- 自定义表盘仍在研究中

## 安全

仓库不包含任何真实设备密钥、MAC、内网地址。