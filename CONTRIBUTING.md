# 贡献指南

## 提交前

- 不要提交真实的设备密钥、MAC、内网 IP、域名或日志；
- Python 代码保持可读，新增协议逻辑请附上证据来源；
- Android 代码与现有风格保持一致；
- 涉及协议变更时，同步更新 docs/ 下的说明。

## 本地检查

    python -m compileall -q band8 server mcp_server
    ./android/gradlew -p android :app:assembleDebug

## Pull Request

请描述：变更内容、验证方式、是否涉及协议/密钥处理。
