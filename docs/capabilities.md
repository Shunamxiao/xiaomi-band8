# 小米手环8 能力清单与命令字典

> 来源：Gadgetbridge Xiaomi 协议实现（与官方 App 实测帧交叉验证）
> 通用通道：`POST /api/v1/command` `{type, subtype, payload(hex)}`

## 服务类型（Command.type）

| type | 服务 | 说明 |
|------|------|------|
| 1 | 认证 | 26 随机数 / 27 确认 |
| 2 | 系统 | 电量、设备信息、设置、免打扰、查找 |
| 4 | 表盘 | 列表/设置/删除/安装 |
| 7 | 通知 | 发送/撤销/来电/快捷回复/图标 |
| 8 | 健康 | 活动数据、心率等 |
| 10 | 天气 | 当前/预报 |
| 12 | 日历 | 日程同步 |
| 17 | 日程 | 闹钟/提醒/待办 |
| 18 | 音乐 | 歌曲信息、媒体键 |
| 21 | 通讯录 | 联系人同步 |
| 22 | 数据上传 | 活动文件上传 |

## 系统服务（type=2）

| subtype | 功能 | payload |
|---------|------|---------|
| 1 | 电量 | 空；响应 system.power.battery |
| 2 | 设备信息 | 空；响应 serial/firmware/model |
| 3 | 时间同步 | system.clock |
| 6 | 语言 | system.language{1:code} |
| 7/8 | 相机遥控 读/写 | system.camera |
| 9/21 | 密码 读/写 | system.password |
| 14/15 | 杂项设置 读/写 | system.miscSettingGet/Set |
| 17 | 查找手机 | system.findPhone |
| 18 | 查找手环 | system.findDevice |
| 23 | 免打扰 | system.dndStatus{1: 0 开 / 2 关} |
| 29/30 | 显示项 读/写 | system.displayItems |
| 39 | 运动类型 | system.workoutTypes |
| 43/44 | 手机静音 读/写 | system.phoneSilentMode(Set) |
| 51/52/53 | 小组件屏 读/写/部件 | system.widgetScreens |
| 78 | 设备状态 | 空；响应 basicDeviceState |
| 79 | 设备状态上报 | basicDeviceState |

## 通知服务（type=7）

| subtype | 功能 | payload |
|---------|------|---------|
| 0 | 发送通知 | notification.notification2.notification3 |
| 1 | 撤销通知 | notification.notificationDismiss |
| 2/5 | 来电 拒接/忽略 | notificationId |
| 6/7 | 通知亮屏 读/写 | screenOnOnNotifications |
| 8 | 手机打开 | notificationId |
| 9/12 | 快捷回复 读/写 | cannedMessages |
| 13/14 | 来电回复 发送/确认 | notificationReply |
| 15/16 | 通知图标 请求/查询 | notificationIconRequest |

### 通知 payload 必填字段（实测）

```
Command{type=7, subtype=0, notification{
  notification2{ notification3{
    package(1), appName(2), title(3),
    unknown4(4) = "",
    body(5),
    timestamp(6) = "yyyyMMdd'T'HHmmss",
    id(7),
    key(12) = "0|<package>|<id>|null|<数字>"   // 缺了手环不显示
  }}
}}
```

## 表盘服务（type=4）

| subtype | 功能 | payload |
|---------|------|---------|
| 0 | 表盘列表 | 空；响应 watchfaceList |
| 1 | 设置表盘 | watchface.watchfaceId |
| 2 | 删除表盘 | watchface.watchfaceId |
| 4 | 安装表盘 | 文件分片传输 |

## 音乐服务（type=18）

| subtype | 功能 | payload |
|---------|------|---------|
| 1 | 歌曲信息 | music.musicInfo{state, volume, track, artist, position, duration} |
| 2 | 媒体键（手环→手机） | music.mediaKey{key} |

## 常用调用示例

```bash
# 查找手环
curl -X POST 'http://<server>:8801/api/v1/find'

# 免打扰
curl -X POST 'http://<server>:8801/api/v1/dnd?enabled=true'

# 手机静音
curl -X POST 'http://<server>:8801/api/v1/silent?enabled=true'

# 音乐卡片
curl -X POST 'http://<server>:8801/api/v1/music?state=1&track=Song&artist=Artist&volume=60'

# 任意命令（设备状态 2/78）
curl -X POST 'http://<server>:8801/api/v1/command' \
  -H 'Content-Type: application/json' \
  -d '{"type":2,"subtype":78}'

# 表盘列表 4/0
curl -X POST 'http://<server>:8801/api/v1/command' \
  -H 'Content-Type: application/json' \
  -d '{"type":4,"subtype":0}'

# 查看命令回包
curl 'http://<server>:8801/api/v1/results'
```

## 已支持的自定义能力

- 任意应用通知转发（含图标请求响应链路已在手环侧出现 7/16 查询）
- 服务端主动推送通知
- 查找手环、电量、设备状态
- 免打扰、手机静音、音乐卡片
- 任意 protobuf 命令透传（可用 payload 字段扩展新能力）

## 待验证

- 表盘安装（需要文件分片通道，工作量较大）
- 闹钟/日程写入（type=17，payload 结构较复杂）
- 天气注入（type=10）
- 手环小组件布局（type=2/51-53）

