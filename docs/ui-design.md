# App 与网页控制台 UI 设计说明（阿里 Ant Design 风格）

> 更新时间：2026-10-25
> 适用范围：android/ 原生 App、server/console.html 网页控制台

## 一、调研结论

参考对象：Ant Design 5.x（阿里企业级设计体系）与 Ant Design Mobile。

可直接落地的核心规则：

| 维度 | 规范 | 本项目取值 |
|------|------|-----------|
| 主色 | 品牌蓝 | #1677FF（hover #4096FF，active #0958D9） |
| 页面底色 | 浅灰 | #F5F7FA |
| 卡片 | 白底、8px 圆角、轻阴影 | #FFFFFF / 12dp 圆角 / 低透明度阴影 |
| 文字主色 | 近黑 | #1F2329 |
| 次要文字 | 中灰 | #646A73 |
| 辅助文字 | 浅灰 | #8F959E |
| 分割线 | 极浅灰 | #EFF0F1 |
| 成功 / 警告 / 错误 | 语义色 | #52C41A / #FAAD14 / #FF4D4F |
| 基础字号 | 14px，辅助 12px | 一致 |
| 行高 | 1.5715 | 网页沿用；App 使用系统行高 |
| 控件高度 | 32px（按钮/输入） | App 用 34-42dp |
| 间距 | 8px 栅格 | 8/12/16/20/24 |

信息结构采用 Ant Design 的「分组标题 + 卡片 + 列表项」模式：
每个列表项由「图标 / 标题+说明 / 开关或箭头」三段组成，便于扫读。

## 二、图标方案

不引入第三方图标库（避免包体增大与版本维护成本），
直接使用 Material Icons（Apache 2.0）的 24dp pathData，编译为 Android VectorDrawable：

bluetooth、notifications、watch、server、vibrate、search、
play、stop、refresh、shield、battery、chevron、palette、info、link。

颜色统一用 android:tint 着色，图标底色为 brand_light (#E6F4FF) 的 8dp 圆角块。

## 三、页面结构

App 首页（activity_main.xml）：

1. 标题区：Cindy Band / 小米手环 8 · 本地直连
2. 状态卡（品牌蓝渐变）：连接状态、电量、设备状态、服务端地址
3. 分组「连接」：服务端地址、保存并启动、停止服务
4. 分组「通知」：转发开关、通知震动开关、通知读取权限
5. 分组「工具」：测试通知、本地通知自测、查找手环、停止桌搭

网页控制台（server/console.html）：

- 顶栏：品牌标识 + 运行状态徽标
- 左列：通知与转发配置、推送通知、最近事件
- 右列：运行状态统计、快捷操作、能力说明
- 响应式：宽度小于 860px 自动单列

## 四、震动设置的边界（事实）

手环固件没有「单条通知不震动」的协议字段：

- Notification3 只有 package/appName/title/body/timestamp/id/isCall/key/openOnPhone 等字段
- MiscNotificationSettings 只有亮屏与「仅锁屏时提醒」
- System.vibrationPatterns（字段 38/39）理论上可按通知类型设置振动预设，
  但手环对 2/38 读取不回包，暂未验证出可用 preset

因此当前实现为：

- 震动开：正常下发通知，手环震动并显示
- 震动关：只记录事件（notification_suppressed），不下发，避免打扰

该行为在 App 开关副标题和网页控制台说明卡中均有明示。
后续若拿到振动预设列表，可升级为「显示但不震动」。