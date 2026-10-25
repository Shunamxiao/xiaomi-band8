# 小米手环8 认证协议（已实测验证）

> 状态：**认证链路 100% 跑通**，已用真实手环验证签名与确认流程
> 验证时间：2026-10-24
> 设备：Xiaomi Smart Band 8 NFC（m66nfc），MAC AA:BB:CC:DD:EE:FF
> 参考实现：`band8/client.py`

## 一、通道角色（实测）

| 用途 | 特征 UUID | 说明 |
|------|-----------|------|
| 主机 → 手环 数据 | `00000052-0000-1000-8000-00805f9b34fb` | 写 |
| 手环 → 主机 数据 | `00000051-0000-1000-8000-00805f9b34fb` | 通知 |
| 主机回 ACK | `00000051-...` | 收到下行帧后必须回 `00 00 03 00` |
| 手环回 ACK | `00000052-...` | 收到上行帧后回 `00 00 03 00` |

## 二、帧格式

```
[seq u16 LE][type 0x02][dataType u8][body...]
```

| dataType | 含义 |
|----------|------|
| 0x00 | 明文旧格式 |
| 0x01 | AES-CCM 加密（4 字节 MAC），body = [counter u16 LE][ciphertext‖tag] |
| 0x02 | 明文 protobuf（认证阶段使用） |

ACK 帧：`[seq u16][0x03][status u8]`，status=0 成功。

## 三、认证流程（已验证）

### 1. 请求 App Verify（cmd 26）

protobuf：
```
pyt { 1: version=1, 2: command=26, 3: payload }
payload = va0 { 30: bb0 { 1: randomApp(16B) } }
```

编码后示例（随机数不同）：
```
08 01 10 1a 1a 15 f2 01 12 0a 10 <16B random>
```

### 2. 手环响应

```
pyt { 1:1, 2:26, 3: va0 { 31: bb0 { 1: randomDevice(16B), 2: deviceSign(32B) } } }
```

### 3. 派生会话密钥（关键）

```
derived = HKDF-SHA256(
    ikm  = encrypt_key (16B, 来自 device_db),
    salt = randomApp || randomDevice,
    info = "miwear-auth",
    len  = 64
)

deviceKey = derived[0:16]
appKey    = derived[16:32]
deviceIV  = derived[32:36]
appIV     = derived[36:40]
```

### 4. 校验手环签名

```
HMAC-SHA256(deviceKey, randomDevice || randomApp) == deviceSign
```

> 注意方向：手环签名用 **D‖A**，App 回签用 **A‖D**，两者顺序不同。

### 5. App Confirm（cmd 27）

```
appSign = HMAC-SHA256(appKey, randomApp || randomDevice)
deviceInfo = protobuf { 1:0, 2:float(SDK_INT), 3:MODEL, 5:region }   // 可自定义
encryptedInfo = AES-CCM(appKey, nonce=appIV||0x00000000||0x00000000, deviceInfo, tag=4B)
pyt { 1:1, 2:27, 3: va0 { 32: ya0 { 1: appSign, 2: encryptedInfo } } }
```

手环响应：
```
pyt { 2:27, 3: va0 { 33: { 1: 1 } } }   // code=1 认证成功
```

### 6. 加密业务帧

```
counter += 1
nonce = appIV(4B) || 0x00000000 || counter(u32 LE)
cipher = AES-CCM(appKey, nonce, plaintext, tag=4B)
frame  = [seq u16][0x02][0x01][counter u16 LE][cipher]
```

## 四、验证记录

- 手环返回的设备签名与本地 `HMAC(deviceKey, D||A)` **逐字节一致**
- cmd 27 返回 `08 01`（code=1）
- 加密帧被手环接受（返回 ACK，链路不断开）

## 五、尚未完成

业务命令号（模块列表 / 电量 / 通知）仍需确定。已知：
- 认证使用 protobuf 命令号 26/27
- 业务帧为加密 payload，但明文结构（protobuf 命令 vs 旧式 module 包）尚未最终确认
- 建议下一步：抓取官方 App 一次完整业务交互的下行帧（需解决 `:device` 隔离进程的附加问题），
  或从 `com.xiaomi.wearable` 的命令构造类中提取命令号


## 六、业务层协议（已跑通）

业务消息 = `XiaomiProto.Command` protobuf：

```proto
message Command {
  required uint32 type = 1;      // 服务类型
  optional uint32 subtype = 2;   // 命令号
  optional Notification notification = 9;
  optional System system = 4;
  ...
}
```

服务类型：1=认证 2=系统 7=通知 10=健康 14=日历 19=日程 20=音乐

已验证命令：

| 功能 | type | subtype | 结果 |
|------|------|---------|------|
| 读电量 | 2 | 1 | 成功，返回 28% |
| 查找手环（震动） | 2 | 18 | 已发送 |
| 推送通知 | 7 | 0 | 已发送 |

### 发送帧格式

```
[00 00][0x02][flag][index u16 LE (仅加密)][payload]
flag = 1 加密 / 2 明文
加密: AES-CCM(appKey, nonce=appIV||0||index(u32 LE), plaintext, tag=4)
index 从 1 开始递增（0 留给认证）
```

### 接收帧格式

```
[00 00][0x02][flag][ciphertext]
解密: AES-CCM(deviceKey, nonce=deviceIV||0||0, ciphertext, tag=4)
注意：下行帧不带 index，nonce 固定用 0
```

### 通知 payload

```
Command{ type=7, subtype=0, notification=Notification{
  notification2=Notification2{
    notification3=Notification3{
      package=1 (string), appName=2, title=3, body=5,
      timestamp=6 ("yyyyMMdd'T'HHmmss"), id=7 (uint32),
      openOnPhone=13 (bool)
    }
  }
}}
```

### 电量响应解析

```
Command{type=2, subtype=1, system=4{
  2: { 1: { 1: percent, 2: state, 3: {1:1} } }
}}
```

## 七、参考实现

- `band8/band8_client.py`：完整客户端（认证/加密/电量/查找/通知）
- `band8/client.py`：仅认证的最小实现

