# vivo / iQOO TWS 家族适配

## 适配边界

vivo 系支持分成两层：

1. **家族候选协议**：名称命中 vivo/iQOO TWS 家族后，建立 vivo GAIA
   RFCOMM 通道，查询 `0x0207/0x8207` 左右耳/盒电量和
   `0x0130/0x0230` 降噪状态；合法响应分别确认并开放相应能力。
2. **具体型号差异**：具体 Adapter 只覆盖已有证据的 GAIA 版本、查询载荷、
   设置后缀或端点差异，不复制通用协议状态机。

家族默认画像来自公开手工逆向，并由 Air3 Pro 实测和 TWS 3e 项目交叉确认
公共 vendor、命令号及三态编号。型号名称只负责 Adapter 选择，`WireConfig` 只负责
GAIA 版本和命令载荷，Socket 和生命周期仍由系统模块统一管理。

## 当前能力矩阵

| 型号范围 | Adapter | 协议画像 | 能力 |
|---|---|---|---|
| vivo TWS Air3 Pro | `VivoTwsAir3ProAdapter` | v3，`mode 04 00` | 实机验证的左右耳/盒电量、降噪、关闭、通透 |
| vivo TWS 3e | `VivoTws3eAdapter` | v3，`mode 03` | 报告确认后的三态降噪、电量、双击、长按循环、DeepX、佩戴检测 |
| 其他 vivo TWS | `VivoEarbudAdapter` | 家族默认 v4，`mode 03 01` | 合法响应后开放私有电量和三态降噪；始终提供流转、音量 |
| iQOO TWS 家族 | `VivoEarbudAdapter` | 家族默认 v4，`mode 03 01` | 合法响应后开放私有电量和三态降噪；始终提供流转、音量 |

TWS 3e 优先使用 vivo UUID `00000837-d102-11e1-9b23-00025b00a5a5`
进行正常 SDP 连接；公开实现记录的 RFCOMM channel 13 仅作为连接回退。
其公开项目本身通过主动 JSON 状态读取左右耳电量，没有发送 `0x0207`；
正式模块依据家族一致性发送同一个只读查询，后续若出现兼容性报告再为该型号
覆盖电量策略。

## 已登记零售名称

- vivo TWS、TWS 1、TWS 2、TWS 2e、TWS 3、TWS 3 Pro、TWS 3e、TWS 5e
- vivo TWS A1、A1 Pro、Air、Air Pro、Air2、Air3 Pro、Neo、X1
- iQOO TWS 1、Air、Air Pro

`vivo TWS Air200` 作为公开 Air2 服务发现记录中的实际设备名，归一到
`vivo TWS Air2`。目录用于诊断与后续 Adapter 配置覆盖；未来未登记但保持
`vivo TWS` 或 `iQOO TWS` 命名格式的型号也会选择家族默认画像。

## 协议画像

| WireConfig | GAIA | 降噪查询载荷 | 降噪设置载荷 | 绑定状态 |
|---|---:|---|---|---|
| `FAMILY_DEFAULT_V4` | 4 | `00` | `mode 03 01` | vivo/iQOO 家族默认画像 |
| `AIR3_PRO_CAPTURED` | 3 | 空 | `mode 04 00` | Air3 Pro，已实机验证 |
| `TWS_3E_V3` | 3 | 空 | `mode 03` | TWS 3e，公开实现 |

`WireConfig` 是 Adapter 内嵌的不可变字节配置，不包含零售名称、MiLink 能力或蓝牙
连接代码。家族 Adapter 选择默认配置；增加可参数化的新协议差异只需登记配置，并由
具体型号 Adapter 覆盖选择，无需复制一套 `ProtocolSession`。

家族及具体型号 Adapter 初始均不声明噪声控制。只有合法降噪状态报告才能把三态能力写入
当前 Adapter 快照并通知 MiLink；握手本身不开放降噪控制。协议无响应时
不会留下可点击但无法执行的控制入口。

## Air2 限制

公开 BlueZ 记录确认 Air2 暴露 `0837/0838` 自定义 GATT 服务，但这不能证明
它一定同时暴露当前 Classic RFCOMM 入口。当前按 vivo 家族默认画像尝试
RFCOMM；若真机报告确认 Air2 仅支持 GATT，则为 Air2 增加独立传输配置，
不能把 BLE 特征值 UUID 当作 RFCOMM 服务直接连接。

## 证据来源

- Air3 Pro：本项目实机抓包，见
  [`vivo-tws-air3-pro-protocol.md`](vivo-tws-air3-pro-protocol.md)。
- TWS 3e：<https://github.com/moculll/ScrewVivoTWS>
- 家族默认 v4 画像：<https://github.com/Star-ZER0/Pods-Protocol-Reverse-Engineering>
- Air2 GATT 服务：<https://github.com/bluez/bluez/issues/687>
- 其余型号名称：本地留存的 vivo 官方 App 反编译常量，仅作为家族身份依据。

## TWS 3e 日常设置

仅 `VivoTws3eAdapter` 接收公开实现的 `A102|10084` JSON 状态；不向 iQOO、
Air3 Pro 或家族 v4 画像外推这些字节。来源固定为 ScrewVivoTWS
`81beeef0aaca22c7018ba03863c4f0ef759fde9f`，使用协议事实独立实现，未复制 Qt
代码、资源或厂商图片。本项目尚未获得这些新增控制的实机复核。

原始传输链路可核查：`VivoController/VivoController.h` 的 `VivoDevice::read`
直接读取 RFCOMM `socket->readAll()`；`ScrewVivoTWS.cpp` 定时器回调把结果传入
`parseData`（133–153 行），再交给 `getJsonToSettingStorage`（87–129 行）。
JSON 是该公开实现直接处理的耳机 RFCOMM 数据，不是 Android App IPC 状态。
本项目改用有界 8 KiB 流式对象提取，支持拆包、粘包、字符串转义及重置。

- 双击：JSON `double_click_l/r` 报告 `3/4/6/7/255` 分别代表语音助手、播放暂停、
  上一首、下一首、无操作；写入 `0102` 的值则为 `0/1/2/3/6`，右耳额外置 `0x10`。
  每次只写一耳，不使用上游会覆盖命令号的双耳 helper。
- 长按：`long_click_l/r` 报告 `8/9/10/11/255`，对应降噪关闭、通透关闭、降噪通透、
  三态循环及无操作；`0131` 写入 `05 left right`。两耳均获得合法报告后才允许写入，
  修改一耳时保留另一耳最新确认值。
- DeepX：`eq_type` 的合法值为 `0/1/2/3/5`，写入 `0118`；不接受保留值 `4`。
- 佩戴检测：`wear_enable` 只接受 `0/1`，写入 `0103`。
- 同一 JSON 中的 `C=left_right` 与 `noise=0/1/2` 可分别确认电量与噪声能力；
  不根据任一高级设置字段推定另一个功能存在。

以上写帧依据 `VivoController.h` 的 helper 与 `getBaseCommandMap`（229–235 行）。
`0150` 通话双击/长按写格式也有来源，但 `A` 的回读语义尚未明确，源程序未将它解析
为通话设置。该命令仅保留 codec 测试，不开放用户请求或 UI，等待完整回读证据。
公开源码没有高级设置主动查询命令，因此不猜测 `0202/0218` 等命令号。
所有新设置使用 DEVICE_REPORT 确认；写入不会乐观更新选中值，必须等待后续
`A102|10084` 报告。常规刷新仍只发送既有握手、电量及降噪查询。

状态使用独立 `VivoTws3eFeatureState`，请求使用 `VivoTws3eControlRequest`，经现有
IPC 与 Adapter 合同传递。合法报告替换设置快照；缺失字段隐藏对应控件，未知枚举、
越界或错误类型拒绝整份报告。协议重置清除设置证据。厂商控制 App 接管、断连、
握手丢失、地址或会话变化后，详情页关闭并退订，不继续发送控制。

原生卡片保留已确认的三态噪声选项，另提供“高级设置”。若只有设置报告，卡片只显示
设置入口，不出现噪声按钮，不制造噪声状态。详情页提供左右双击、左右长按、DeepX
和佩戴检测；未报告项目不显示。测试覆盖字节、双击读写映射、流拆包、非法值、型号
隔离、快照替换与重置、IPC 往返，以及原生卡片恢复和设置页会话/读回行为。
