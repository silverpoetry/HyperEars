# 华为 FreeBuds 协议记录

## 验证范围

- 依据：melianmiko/OpenFreebuds（GPL-3.0）`driver/huawei` 包与对应测试夹具交叉验证。
- 耳机：HUAWEI FreeBuds Pro 3、HUAWEI FreeBuds 5i。
- 传输：Bluetooth Classic RFCOMM SPP；Pro 3 使用 Channel 1，5i 使用 Channel 16。
- 厂商 App：华为智慧音频。
- 证据等级：FreeBuds Pro 3、FreeBuds 5i 实机验证（5i 以真实设备 HCI 捕获记录为起始证据）；其他家族候选依据公开实现，
  在合法协议响应后开放对应能力。

## 帧格式

所有帧以 `5A` 开头，随后为两字节大端长度、固定 `00`、命令号、参数 TLV
序列与两字节校验：

```text
5A <len:2BE> 00 <cmd:2> <param TLVs...> <crc:2>
```

- `len` = 从固定 `00` 起至最后一个参数字节的字节数（帧总长 = `len + 5`，
  CRC 不计入）。实测帧均小于 256 字节，高位长度字节为 `00`，但字段按 16 位读写。
- 参数 TLV：`<type:1> <len:1> <value...>`；空参数为 `<type> 00`。
- 校验：CRC16/XMODEM（poly `0x1021`，init `0x0000`，无反射、无最终异或），
  覆盖 CRC 之前的所有字节，与 OpenFreebuds 表格实现逐字节一致。
  HyperEars 对接收帧执行同一 CRC 校验；校验失败的帧不能成为握手或能力证据。

## 命令表

| 功能 | 命令 | 说明 |
|---|---|---|
| 电量查询/响应 | `01 08` | 读取参数 1/2/3 |
| 电量主动上报 | `01 27` | 连接后设备主动推送 |
| 噪声状态读取/回传 | `2B 2A` | 读取参数 1/2 |
| 噪声模式/档位写入 | `2B 04` | 参数 1 为 `[mode, level]` |
| 模式变更通知 | `2B 03` | 设备侧按键切换；收到后应重新读取 `2B 2A` |

## FreeBuds 5i 抓包特有证据

以下内容来自一台 FreeBuds 5i 的 Android `btsnoop_hci` 文件和同步人工时间线；原始抓包未提交。
抓包先确认协议事实，随后在 FreeBuds 5i 上完成 HyperEars 端到端实机验证（识别、私有连接、
状态读取、控制写入与卡片回读）。

- 控制面使用 BR/EDR ACL、L2CAP RFCOMM PSM `0x0003` 和 RFCOMM DLCI `0x21`；当前型号适配固定
  使用 RFCOMM Channel 16。
- 抓包中的设备身份包括 `HUAWEI FreeBuds 5i`、固件 `2.1.0.218(F001H003C00)`、协议类型
  `BTFT0014-000145` 和 VID `0xd45fa8ea`。这些字段只用于记录型号和固件差异，不能单独证明
  控制能力。
- 私有控制 DLCI 与 HFP/兼容性 AT 数据通道语义不同；`2B 04` 模式写入只在私有控制通道中观察到，
  不能把 `AT+BRSF`、`AT+IPHONEACCEV` 或华为电量扩展命令当作同一协议帧处理。
- 本次抓包先出现私有初始化、能力查询和状态查询，随后才出现 `2B 04` 模式写入；当前适配因此
  保持初始化、能力确认、模式写入和状态回读四个阶段。

## 电量帧

```text
01 <27|08> 01 01 <global> 02 03 <left> <right> <case> 03 03 <...> <crc>
```

- 参数 1：整机电量百分比 `0..100`。
- 参数 2：`[left, right, case]` 组件电量，字节直接表示 `0..100`。
- `0` 表示该部件未连接（单耳佩戴或不在盒内）。
- 参数 3 含 `0x01` 即充电中（任意位置）。该标志是整机级的，协议不区分
  哪个组件在充电；HyperEars 只把整机充电状态映射到 `overall`，左右耳与
  充电盒的充电字段保持 `false`，不推测组件级充电。
- 实测向量（OpenFreebuds 夹具）：参数 1 = `40`（64%）、参数 2 = `10 20 30`
  （左 16%、右 32%、盒 48%）、参数 3 = `00 01 00`（充电中）。

## 噪声状态与写入

### 状态回传（`2B 2A`）

```text
2B 2A 01 02 <level> <mode> <crc>
```

**注意字节序不对称**：读回传参数 1 为 `[level, mode]`，模式字节在第二位。
依据：OpenFreebuds `anc.py` 公开参考实现 `active_mode = data[1]`，以及
Melianmiko 的 FreeBuds 4i 协议笔记（“Second — current ANC mode”）。

| mode | 含义 |
|---|---|
| `00` | 关闭（normal） |
| `01` | 降噪（cancellation） |
| `02` | 通透（awareness） |

level 含义依赖当前 mode：

| mode | level | 含义 |
|---|---|---|
| `01`（降噪） | `00` | 均衡（normal） |
| `01`（降噪） | `01` | 舒适（comfort） |
| `01`（降噪） | `02` | 超强（ultra） |
| `01`（降噪） | `03` | 动态（dynamic，Pro 3 支持） |
| `02`（通透） | `01` | 人声增强（voice_boost） |
| `02`（通透） | `02` | 均衡（normal） |

关闭模式不携带有效档位。

### FreeBuds 5i 模式证据

FreeBuds 5i 的抓包记录确认标准模式写入使用同一 `2B 04` 命令和 Channel 16。型号专属档位
测试失败，待完善；当前不建立 5i 专属档位状态或控制请求。

| 模式 | 写入参数 `[mode, level]` | 证据 |
|---|---|---|
| 关闭 | `[00, 00]` | 抓包确认；实机复核 |
| 透传 | `[02, FF]` | 抓包确认；实机复核；中文语义与人工操作顺序对应 |
| 降噪 | `[01, FF]` | 抓包确认；实机复核；保持当前档位 |

### FreeBuds 5i 档位候选（测试失败，待完善）

抓包中还观察到以下降噪档位候选值；档位中文名称和 `level` 映射仍有推断成分，且当前设备
测试未能完成稳定写入与 `2B 2A` 回读验证：

| 候选档位 | 写入参数 `[mode, level]` | 当前结论 |
|---|---|---|
| 均衡 | `[01, 00]` | 抓包候选；设备测试失败，待完善 |
| 轻度 | `[01, 01]` | 抓包候选；设备测试失败，待完善 |
| 深度 | `[01, 02]` | 抓包候选；设备测试失败，待完善 |
| 智能动态 | `[01, 03]` | 抓包候选；设备测试失败，待完善 |

以上候选值仅保留在研究记录中，不代表 HyperEars 当前支持四档手动调节；适配器继续只开放
标准降噪、关闭和通透三态。

### 模式与 Pro 3 档位写入（`2B 04`）

```text
2B 04 01 02 <mode> <level> <crc>
```

写命令参数 1 为 `[mode, level]`，模式字节在第一位（与读回传相反）：

- 关闭：`[00, 00]`
- 降噪（保持档位不变）：`[01, FF]`
- 通透（保持档位不变）：`[02, FF]`
- Pro 3 降噪并指定档位：`[01, 00|01|02|03]`
- Pro 3 通透并指定档位：`[02, 01|02]`

写入后设备不会主动回传状态；参考客户端在每次写入后重新读取 `2B 2A`
以同步权威状态。HyperEars 在每次写入后发送 `2B 2A` 读回（`DEVICE_REPORT`
确认）。

## 协议能力确认流程

按“标准能力起步、有效响应确认”：

- 适配器初始只保留系统整机电量与媒体流转，不开放私有模式控制。
- 私有通道连接后进入 `PROTOCOL_HANDSHAKE`，首帧发送 `01 08` 与 `2B 2A`。
- 收到 CRC、结构和字段均合法的电量帧后，开放组件或整机电量并切换私有电量来源。
- 收到 CRC、结构和字段均合法的模式状态帧后，按具体型号 Profile 开放模式；只有 Pro 3
  开放 `huawei.freebuds_pro3.anc_level` 类型化档位状态。
- 模式写入由读回确认（`DEVICE_REPORT`）；Pro 3 档位写入也使用同一确认路径；`2B 03` 设备侧变更通知
  触发一次 `2B 2A` 重新读取。
- 具体型号使用已确认的 Channel；家族候选由同一 Adapter 依次试探 Channel 1、16。单个
  端点在有限次数内未获得有效响应时进入下一端点，全部失败后保持标准耳机回退。

## FreeClip 2（开放式夹耳）

FreeClip 2 使用 `5A` SPP 帧协议与 RFCOMM Channel 1，但无降噪、通透或档位：

- 初始握手只发送 `01 08` 电量查询，不发送 `2B 2A` 噪声状态查询。
- 收到 CRC、结构与字段均合法的电量帧后开放组件电量并确认协议。
- 不开放任何噪声模式，`2B 2A` / `2B 04` / `2B 03` 均不建模。

识别与传输证据：HyperEars 实机验证中，设备实测蓝牙 Class 为 `0x001F00`（未分类），
因此按规范化名称 `huaweifreeclip2` / `freeclip2` 精确选择候选，再进入协议确认；RFCOMM
连接确认使用 SCN 1（标准 SPP UUID `00001101-0000-1000-8000-00805f9b34fb`），电量读取
与组件电量显示已验证。
原版 FreeClip 继续使用华为家族候选，未在缺少同等实机证据时声明为 FreeClip 2 的同协议
精确型号。

## 已观察但尚未建模

- `01 07`：设备信息帧（固件版本、序列号等），参考客户端读取参数 0..31，
  当前不消费。
- FreeBuds 5i 的 `2B 2A` 状态读回与三态写入语义已由实机验证复核；长遥测帧字段、分片边界和
  主动通知机制仍需更多设备或独立操作样本确认。
- 音质偏好、EQ 预设、自动暂停、双连、佩戴检测、手势与低延迟配置帧：
  Pro 3 驱动声明支持，但 HyperEars 首版不建模。

## 与 HyperOS 接入的边界

本阶段确认的是耳机私有能力：组件电量、三态切换，以及 Pro 3 的档位读写与状态回传。
音频路由、系统蓝牙连接和设备流转入口属于 HyperOS 集成层；正式模块复用
`protocol` 与 `integration` 模块，不在蓝牙进程引入额外 Hook。已确认的
Pro 3 型号专属档位状态与请求经 `huawei.freebuds_pro3` 命名空间接入 v2.0.0
类型化传输架构，与标准模式状态独立更新；5i 仅使用标准模式状态。

## 日常设置：精确型号与回读门控

本轮新增代码参考 OpenFreebuds `0b4f14c0b500d491f0fb4ea38f2984ec334e3649` 与
HuaweiPods `dc5b9d14e9030be95b178699d674d1a3217e1e6d` 的协议事实，独立接入本项目
Adapter / ProtocolSession / WireCodec 与 typed feature/request；没有移植上游蓝牙线程、
Qt/Compose 界面、厂商图片或乐观成功状态。下列新增功能尚待 HyperEars 最终版本实机复核。

具体候选画像：

- 既有 Pro 3：EQ 预设与已报告自定义槽、佩戴检测、双设备、双击、左右长按动作与循环、滑动。
- 既有 5i：EQ 预设、佩戴检测、双设备、双击（含通话）、三击、左右长按与循环、滑动。
  保持 Channel 16，**不改变此前 ANC 档位试验失败的限制**。
- 既有 FreeClip 2：EQ、佩戴检测、双设备、双击、三击、左侧长按助手/音量与滑动；
  保持无 ANC，不发送噪声循环查询。其 EQ 采用 HuaweiPods 捕获的 `1/10/3/9`
  （默认/运动增强/高音/人声），不沿用 OpenFreebuds 泛化的低音 `2`。
- 新精确 4i、HONOR Earbuds 2 / 2 SE / 2 Lite：Channel 16，佩戴检测、左右双击、
  左侧长按动作与噪声循环，匹配 OpenFreebuds `buds_4i.py` 与驱动注册表。
- 新精确 FreeBuds Pro：Channel 1，双设备、左右长按动作与循环、滑动；
  Pro 2 另有佩戴检测与 `1/2/3` EQ 预设，不为原 Pro 猜测这些功能。
- 新精确 6i：Channel 1，5i 同类设置，另允许编辑已报告的十段自定义 EQ 槽。
- 新精确 Studio：Channel 1，头戴式、汇总电量、佩戴检测；源画像 EQ 设置为 `wo_read`，
  缺少回读而不开放 EQ。不会从共享电量帧投影左右耳/充电盒。
- 新精确 FreeLace Pro 2：Channel 1，汇总电量、双设备、已报告 EQ 与自定义槽、
  左侧长按与循环；不添加该画像未声明的佩戴检测。

未知 Huawei 家族、旧 FreeLace Pro、FreeBuds 3 的 HFP / 新 SPP 区别、Pro 4 的
冲突证据均保持原边界。本轮不将某个现代 SPP 画像自动套用到这些型号。

### 设置字节与来源

`OpenFreebuds/openfreebuds/driver/huawei/handler/` 是主要协议来源：
`config_auto_pause.py`、`config_equalizer.py`、`abstract/multi_tap.py`、
`action_long_tap_split.py`、`action_swipe_gesture.py`、`dual_connect/handler.py`。
型号查询许可来自 `driver/per_model/` 中对应构造器，不来自品牌名称或 App 安装状态。
HuaweiPods 的 `HuaweiWearDetectionController.kt`、`HuaweiEqualizerCodec.kt` 与
`HuaweiFreeClip2Controller.kt` 提供捕获帧和型号差异交叉证据。

| 功能 | 读回 | 写入 | 字段与约束 |
|---|---|---|---|
| 佩戴检测 / 自动暂停 | `2B11` | `2B10` | TLV 1，严格 `0/1` |
| 双设备连接开关 | `2B2F` | `2B2E` | TLV 1，严格 `0/1` |
| EQ | `2B4A` | `2B49` | 当前预设 TLV 2；可选列表 TLV 3；写预设 TLV 1 |
| 双击 | `0120` | `011F` | 左/右 TLV 1/2，通话 TLV 4，选项 TLV 3 |
| 三击 | `0126` | `0125` | 同上，但仅画像声明的字段可写 |
| 长按动作 | `2B17` | `2B16` | 左/右 TLV 1/2；关闭 `-1`、噪声切换 `10` |
| 长按循环 | `2B19` | `2B18` | `1/2/3/4` 表示相应二态或三态循环 |
| 滑动 | `2B1F` | `2B1E` | `-1/0` 关闭/音量；写入同时携带 TLV 1/2 |

常规双击/三击动作采用有符号值 `-1/1/2/7/0`（关/播放暂停/下一首/上一首/助手）；
写入选项同时受具体画像和设备返回列表约束。FreeClip 2 双击 `7` 表示切换空间音频，
三击 `7` 才表示上一首；6i 双击限播放/下一首，三击限下一首/上一首/关闭。
FreeBuds 3 的不同动作码不进入这些画像。
长按右侧与通话字段按各画像单独开放，不从左侧报告推定右侧支持。

自定义 EQ 仅允许已有报告中的槽 `100..102`：`2B4A` TLV 8 每条记录严格 36 字节，
包含 ID、十段增益和 24 字节 UTF-8 名称；十段值限制 `-60..60`。写入 `2B49` 保留
当前槽 ID 与名称，以 TLV `1/2/3/4/5` 发送 ID、段数、增益、名称及操作 `1`。
不猜测新槽分配、不删除预设，也不向不含 custom handler 的型号开放编辑。
自定义写请求携带创建草稿时的完整槽快照（ID、名称与原增益），在 Adapter 与 Session
再次比较；同 ID 槽已重命名、替换或增益改变时拒绝旧草稿，即使 UI 尚未收到观察者回调。

### 状态、生命周期与原生展示

名称只允许只读探测，`HuaweiDailySettingsFeatureState` 在 CRC、TLV 长度、命令、
值域通过校验后才逐项出现。写 ACK（包括 `7F` 包络）不更新设置状态；写入统一使用
DEVICE_REPORT 并发送对应只读查询，不采用上游部分 helper 的乐观 UI 更新。
每种手势的有效回读替换该项快照，缺失耳侧撤销对应控件；其他独立设置状态保留。
有效 EQ 报告即使只包含本型号未开放的预设，也替换旧快照并撤销旧选择入口；
结构损坏的报告仍忽略。Studio/FreeLace Pro 2 仅有组件电量而没有合法汇总电量时，
不确认私有电量或覆盖现有系统读数。协议重置清除全部设置与 ANC 档位证据。
没有新增定时轮询、连接线程或重连回放设置。

原生卡片保留确认过的噪声按钮并添加“高级设置”；只有设置证据时只显示入口，不制造
噪声能力。详情页包括回读支持的开关、预设、每侧手势、Pro 3 原有噪声档位及自定义
EQ 草稿。滑动仅修改本地草稿，点击保存才发送；设备回读才改变已确认设置。界面遵守
宿主昼夜配色，所有权/会话/地址/握手/连接变化时关闭并退订，宿主回调异常被隔离。

低延迟、质量偏好、配对设备列表操作、语音语言和现代机型更多智能功能不由本轮公共
设置画像推定。特别是旧型号低延迟的“保存并重连回放”不等同于设备权威回读。

## Low latency and sound-quality preference (2026-10-28)

Source pin: OpenFreebuds `0b4f14c0b500d491f0fb4ea38f2984ec334e3649`.
`openfreebuds/driver/huawei/handler/low_latency.py` reads2B6C field2 and writes
2B6C field1 Boolean, then waits one second before reading again. The published
`test/test_low_latency.py` supplies query `5A0005002B6C0200B820` and enabled write
`5A0006002B6C010101A411`; those exact bytes are regression fixtures.
`handler/sound_quality_preference.py` reads2BA3 with empty field1 and obtains its
value from field2:0 connectivity-first,1 quality-first. Writes use2BA2 field1,
followed by an authoritative2BA3 query. This preference is not the currently
negotiated Android audio codec and does not report codec identity.

Current local profiles with both executable handlers: FreeBuds Pro3,5i,6i,
FreeClip2 and FreeLace Pro2. Pro2 declares only sound-quality preference and
therefore gains no latency query/control. Pro,4i/Honor Earbuds2 variants and
Studio remain unchanged. Source `driver/constants.py` maps Pro4 and FreeClip1
to its Pro3 driver, but this does not broaden local Pro3 matching or overwrite
local Pro4's separate qualified profile. Pro5/SE2/SE4 source declarations are
outside this existing-profile slice.

Both nullable settings become available only after a strict single-byte0/1
field2 response for that exact model's enabled handler. Wrong field, write
command, truncated/multi-byte/out-of-range response, or7F ACK/error envelope
cannot confirm a preference. A latency write schedules one replaceable adapter
state request at1000ms; its targeted query contains only2B6C field2. Runtime
state-request ownership/cancellation remains in the existing session dispatcher,
including reset, channel replacement and vendor handoff. No optimistic values,
automatic replay or Android-side model switch is added. Voice language remains
excluded because its handler does not supply actual selected-language readback.

Native daily settings show low-latency on/off and connection-stability/quality
preference only with current evidence; callbacks revalidate session/owner and
support. Source/fixture/UI tests are distinct from physical device acceptance.

Native Huawei settings entry also accepts a confirmed, supported ANC/transparency level belonging to the current noise mode, without requiring or fabricating a daily-settings report. Invalid/missing levels revoke this entry when no other settings remain.

### Additional OpenFreebuds model profiles

Pinned OpenFreebuds `0b4f14c0` executable `driver/huawei/driver/per_model/buds_se.py`,
`buds_se_2.py`, `buds_se_4.py`, and `buds_pro_5.py` qualify four exact-name profiles.
SE inherits RFCOMM channel 16 from `driver/generic/spp.py:17`: battery and double-tap only.
SE 2 uses channel 1, double/triple taps, call-only hold, presets 1/2/3/9 and low latency;
neither SE model queries or accepts ANC. SE 2 hold suppresses both left/right controls;
call state/write remains parameter 4, preserving distinct call evidence instead of upstream's
accidental read of the right-side byte. SE 4 ANC uses channel 1, ANC levels 0–3 including
dynamic, double/triple taps, extra hold actions and noise-cycle choices, presets 1/2/3/9 and
low latency. Its driver disables voice-boost support; no transparency-level selector is exposed.
Pro 5 uses channel 1 and the same explicitly declared level and daily handler membership as Pro 3,
including custom EQ, wear detection, multipoint, quality preference and low latency.
All controls still require their own live reports; exact names only select a query profile.
The shared level extension preserves existing Pro 3 wire bytes. Voice language remains excluded
because the upstream handler has no selected-language readback. These additions have source and
unit-test coverage; no new physical-device verification is claimed.

## FreeBuds 3 independent SPP profile

OpenFreebuds 0b4f14c0 `driver/per_model/buds_3.py` explicitly selects Channel 1 and
battery/double-tap handlers. Double-tap values are -1 off, 0 assistant, 1 play/pause,
3 ANC cycle and 4 next track. These differ from later model values; native labels and
writes preserve that difference. Gesture support does not establish a direct ANC command.
This SPP profile does not query or accept the later family ANC protocol. HuaweiPods'
legacy ANC path also sends RFCOMM packets despite its HFP-related class names, but its
FreeBuds3 profile provides no reliable ANC readback. Those write-only controls remain
unported; the HFP battery hook is a separate path. Voice-language and in-ear telemetry
handlers do not imply confirmed language selection or an auto-pause setting.
Model identification remains exact, and actual fields require valid reports. Hardware
validation of this new profile remains pending. Pro5 EQ labels also follow its explicit
5/default, 1/bass, 2/treble, 9/voice source mapping rather than the generic 1/default list.

## Pro 4 and first-generation FreeClip query candidates

OpenFreebuds 0b4f14c0 `driver/constants.py` routes both exact names to
`driver/huawei/driver/per_model/buds_pro_3.py` on Channel 1. This is source alias
membership, not hardware validation: that driver calls its common handlers "May work"
and marks several advanced handlers untested. Existing strict EQ, wear, multipoint,
low-latency and quality reports independently enable the corresponding controls;
queries and model names alone enable none. EQ labels follow that driver's 5/default,
1/bass, 2/treble, 9/voice mapping. Custom editing still needs a reported custom slot.

HuaweiPods dc5b9d14 `pods/DeviceCapabilities.kt` qualifies Pro 4 basic ANC but does
not qualify its discrete levels or transparency. This profile therefore accepts only
ANC/OFF, has no level extension, and restricts the long-press noise cycle to value 1
(off/on). FreeClip has no ANC: no noise queries, accepted noise reports, level controls
or cycle queries. Its alias driver's long-press action set is reduced to -1 (disabled);
no newer FreeClip 2 assistant/volume actions are inferred. Double-tap and swipe retain
only their existing source action sets and require their own reports. Exact FreeClip
matching does not change FreeClip 2's separately validated battery path. Both newly
added model profiles and daily controls await physical-device verification.

### First-generation FreeLace Pro

The exact channel-16 profile follows OpenFreebuds `lace_pro.py`. Its 2-second cooldown is
before each socket connection, inside the cancellable candidate job, with ownership checked
again afterwards. This does not add a global Bluetooth delay. ANC levels are normal/comfort/ultra;
dynamic ANC and voice boost are excluded. Power-button double tap accepts only -1/12, while
combined long press accepts -1/3/5/6/9. Both commands mirror the value into fields 1 and 2;
only field-1 readback grants their single physical-control row. Hold value 9 retains a neutral
label because the source `noise_control_off_an` has no verified translation. The headphone
form factor does not suppress valid component battery reports. Reconnection clears reports.
Voice-language writes remain with the vendor app because the source query reports available
languages without an authoritative current-language value. The new profile awaits device feedback.

## Paired-device management

OpenFreebuds `0b4f14c0`, `openfreebuds/driver/huawei/handler/dual_connect/`
`handler.py`, `models.py`, `constants.py` and `constants/spp_commands.py` provide
this separate device-list contract. Existing executable multipoint profiles permit
the query; FreeBuds6i and FreeLace Pro2 explicitly disable auto-connect management.
A global multipoint toggle report does not substitute for a paired-device list.

Enumeration2B31 sends field1 empty. Each row reports signed big-endian count(field2)
and zero-based index(field3), exact six-byte MAC(field4), connection byte(field5;
positive=connected,9=playing), preferred boolean(field7), optional-profile auto-connect
boolean(field8), and UTF8 name(field9). The implementation bounds counts to1..32,
integer widths to1..4 and names to the255-byte TLV limit; these are defensive implementation
bounds, not claims about source device limits. Unknown fields remain in the raw
packet snapshot. CRC/envelope parsing and strict field validation precede collection.

Only all distinct indices0..count-1 with unique MACs and a consistent count produce
an actionable snapshot. A new query clears pending rows and immediately invalidates
session write admission; its first valid partial response revokes the published old
snapshot. Conflicting rows lock that round until a new query. Valid2B36 change events
revoke and schedule one existing immediate read-only enumeration command. No timer,
polling or partial-list write is added. A missing-MAC or zero-count packet is not
invented as an empty-list completion. Malformed data and7F acknowledgements never
confirm or remove a device.

Preferred writes2B32 field1=MAC. Execute2B33 uses action field1 connect,2 disconnect,
3 forget,4 enable auto-connect,5 disable auto-connect. Each accepted management write
is followed by full enumeration; no optimistic connected/preferred/name state is
published. Requests pin the full ordered snapshot, local revision and target MAC.
Disconnect/forget require one native confirmation and recheck ownership, current
session and full snapshot at acceptance. Other paired-device operations use the same
current-state guards. The existing multipoint toggle is unchanged.

Fixtures were generated directly by the pinned Python `HuaweiSppPackage.to_bytes`
writer, with source utility/exception modules loaded without starting its platform
backend. For example query is `5a0005002b310100f1ec` and forgetting001122334455 is
`5a000b002b330306001122334455bfc9`. Tests use these literal CRC-bearing frames,
including a two-row enumeration, and do not derive goldens from the Kotlin encoder.
Source/fixture verification does not establish physical device validation.

### HuaweiPods exact-profile completion (dc5b9d14)

Source: `HuaweiPods` commit `dc5b9d14e9030be95b178699d674d1a3217e1e6d`,
`app/src/main/java/moe/chenxy/huaweipods/pods/`. This slice adds eight exact catalog
routes: FreeBuds 4E / 5 / 6 / 7 / 7i, FreeArc, Eyewear (including the source Eyewear 3
alias), and Eyewear 2. Existing FreeBuds 4 keeps its adapter ID and tested channel;
its new daily queries and two ANC levels come from the same source handlers as 4E.
These are source-tested candidates, **not hardware-validated integrations**.

`HuaweiL2capAncController.kt` builds secure standard SPP UUID `00001101-0000-1000-8000-00805f9b34fb`
first, then further candidates including channels 1–8. New routes use that secure
UUID first and the source's secure channel fallbacks. They do not assert one fixed
channel. The source's insecure UUID/SDP variants are not introduced into the shared
transport model by this slice.

`HuaweiAncLevelProfile.kt` proves 4/4E light=1, balanced=0; 5/6/7 adaptive=3,
light=1, balanced=0; and **7i adaptive=1, light=0, balanced=2, deep=3**.
The per-profile map is used for both readback and writes. Existing Pro 3 and 5i
behavior remains unchanged where references disagree. Existing 6i now gains the
report-gated 0/1/2/3 ANC and 1/2 transparency level extension. FreeBuds 7 independently
supports transparency with entry/submode 02; 7i enters with FF and has no guessed
transparency level selector. FreeBuds 5/6 have no transparency control.
`HuaweiRfcommResponseParser.parseAncState` reads `[submode, mode]`, matching the
existing codec; the write remains `[mode, submode]`. Source literal parser fixtures
such as `5A0007002B2A010203015043` prevent an accidental symmetric-byte test.

Actual executable memberships, rather than `DeviceCapabilities` flags alone,
qualify daily queries: `HuaweiWearDetectionController` for 4/4E/5/6/7/7i;
`HuaweiEqualizerCodec.buildBuiltInPresetPacket` and the 5/6/7/7i controllers for
model-specific presets; `HuaweiGestureController` for double/triple/hold actions.
4/4E hold uses **03 noise, 0E song recognition, FF none**, not the other models' 0A.
FreeArc hold is assistant/none; Eyewear 2 double tap is play/assistant/none.
Every control still requires its own valid report, including valid false values;
queries, model names and static profile tables never grant controls.

FreeArc and Eyewear have no ANC query, state acceptance or ANC control.
`HuaweiRfcommResponseParser.parseBattery` explicitly reads left/right indices 0/1;
Eyewear's `hasChargingCase=false` suppresses case projection and permits a two-byte
component report. It does not turn the first ear into an aggregate headset battery.
Other profiles retain the existing strict three-component parser behavior.

Remaining bounded differences are explicit: Eyewear 2 swipe is not routed through
the existing mirrored swipe writer (see the qualification gap below); custom EQ
creation/deletion and 7i's distinct custom operation byte are not borrowed from a
neighboring profile. Special classical EQ packets on 6/7, modern spatial/head-motion
and route-specific quality/multipoint payloads remain vendor-app controls pending
their own full readback/state contracts. FreeArc preset 0A uses the sport label from `ui/components/FreeArcControls.kt`. No firmware/calibration workflow
is added here.

The existing FreeBuds 5i level candidate has a documented failed device test above.
Its standard modes remain unchanged; opening new level controls requires a firmware-qualified
identity and captured readback that resolves that evidence conflict, not merely another source
project's four-value table.

### Independent swipe: FreeArc / FreeBuds 6i / FreeClip 2

HuaweiPods `dc5b9d14` `HuaweiGestureController.kt:114–129,305–321` provides all
three executable paths: query `2B1F` fields 1/2 (empty), report `2B1F` field 1 left
and field 2 right (one byte each), write `2B1E` **only the selected side's TLV**.
`HuaweiRfcommResponseParser.parseSwipeState` requires both components. FreeArc
choices are 00 volume / 01 tracks; 6i additionally permits FF disabled; FreeClip 2
only permits 00 / FF. Advertised choices, when present in field 3, further restrict
these source domains. No model name alone enables a row.

The existing daily typed gesture request now optionally pins the complete reported
gesture, including exact profile ID and every ordered raw TLV. Independent profiles
require this snapshot; old requests without it are rejected by both adapter and
session. Any changed sibling, available choices, opaque field, model or reset rejects
the old request. Valid partial reports revoke the complete-pair write gate; malformed
field widths/duplicate TLVs do not replace a previously valid report. A single-side
write cannot rewrite the sibling and is followed by the exact complete-pair query;
there is no optimistic update. Existing source-qualified mirrored profiles retain
their prior writer and request shape.

Unknown one-byte current values are retained as explicitly unknown read-only values
and shown on both native surfaces, without adding them to the action choices. A
known replacement action can still be sent under the complete raw-pair guard; only
that selected side is written, including when the sibling has an unknown value.
Incomplete pairs remain visible but offer no actions. Native rows distinguish left
and right; delayed callbacks pin the original full snapshot and still pass the
existing session/owner gates.

Eyewear 2 remains a precise separate gap: the source write builder uses the special
raw repeated triplet `[side, side, action]` twice, e.g. right tracks
`5A0009002B1E020201020201DB3C`. This is **not ordinary TLV** and is not borrowed for
other models. The pinned `buildGestureStateQuery(EYEWEAR2)` returns null and no
Eyewear 2 swipe readback capture/query path is provided. Its enum and setter alone
do not justify an independent native workflow. A qualified query plus actual state
capture is required before enabling it.

Tests use upstream literal `5A000E002B1F01010002010003030001FF24DF`, source query
`5A0007002B1F01000200328A`, and independent modified frames with Python
`binascii.crc_hqx(..., 0)` checksums. They cover exact single-side output, model and
raw-sibling staleness, opaque fields, valid partial revocation, malformed preservation,
old mirrored behavior, reset, native unknown-value labels and delayed callbacks.
This is source-level validation; none of these additions claim device revalidation.

### Full custom EQ inventory and CRUD (four explicit OpenFreebuds profiles)

OpenFreebuds `0b4f14c0b500d491f0fb4ea38f2984ec334e3649`,
`openfreebuds/driver/huawei/handler/config_equalizer.py`, supplies the executable
create/select/update/delete workflow. Its `driver/per_model/buds_6i.py`,
`buds_pro_3.py`, `buds_pro_5.py`, and `lace_pro_2.py` explicitly instantiate
`OfbHuaweiEqualizerPresetHandler(w_custom=True)`. Only those four exact profiles
receive this workflow; Pro4/FreeClip alias-driver membership does not enable CRUD.

The three custom IDs are **0x64, 0x65, 0x66 (decimal 100, 101, 102)**. Full query is
`2B4A` fields 1–8. The inventory gate requires selected ID, built-in
list, and explicitly present field 8 containing zero to three unique
36-byte records. Each record carries ID, count 10, ten signed gains, and 24 bytes of
UTF-8 name/padding. Support flag 1 and band count 5 are optional in the OpenFreebuds
response; when present they must not indicate unsupported or a non-ten-band layout.
Names decode strictly up to the first NUL, matching `_parse_custom_mode`; remaining
bytes are opaque and retained in the expected raw snapshot, not required to be zero.
Every record must be understood; duplicate IDs, unknown IDs,
invalid names/curves or truncated records never establish a free slot. An explicit
zero-length field 8 is an empty inventory: the source `HuaweiSppPackage` supports
zero-length TLVs and `on_package` treats an empty custom field as no custom modes.
Absent field 8 is only partial evidence and cannot authorize creation.

Source `_set_current_mode` allocates the lowest unused custom ID, or selects an
existing custom record by sending its complete data. `_change_current_mode` updates
the selected record. `_delete_current_mode` deletes the selected record, then invokes
the full read. The native implementation keeps those boundaries:

- CREATE: explicit user action/name/ten-band curve, lowest free ID, operation 1.
- SELECT: full existing custom record with operation 1, or source built-in ID alone.
- UPDATE: selected custom record, preserving ID/name, operation 1.
- DELETE: selected custom record only, full record plus operation 2, with confirmation.

Custom writes use `2B49` TLVs 1 ID, 2 count, 3 gains, 4 UTF-8 name, 5 operation.
The writable name is bounded to the known 24-byte record capacity; gains remain
-60..60. There is no guessed slot overwrite when all three IDs are occupied and no
rename/firmware/automatic creation path. Existing ten-band UI is reused for creation
and update; the Settings entry also works with a confirmed empty inventory.

Every request pins exact model, a monotonically increasing local revision and the
whole raw inventory including opaque TLVs. The device exposes no revision field;
this local token prevents an old same-content/ABA callback from becoming current.
Partial valid snapshots revoke mutation controls. A CRC-valid inventory that cannot
be interpreted also revokes the prior inventory rather than retaining presumed free
IDs; corrupt CRC frames are ignored. Old SetEqualizer/SetCustomEqualizer mutation
requests are rejected on these four profiles, including during pending readback.

Encoding is side-effect-free. Successful transport write marks the workflow pending,
without optimistic selection/curve/removal, and sends a full readback. Refresh can
request another full read but never clears pending. Only a complete valid report or
reset ends the pending interval. Native editor and deletion confirmation retain their
original snapshot and pass fresh session/owner checks at submission.

HuaweiPods `dc5b9d14` separately confirms the record format and operation-1 curve
writes for 6i/Pro5, but its 7i writer uses operation 0. It provides no direct SPP delete
workflow for 7i/7/SE4/FreeArc; these routes do not inherit operation 2. FreeClip2's
multi-slot/add UI calls `SmartAudioEqualizerClient` via the vendor app, while its
direct custom operation is null. That bridge is not evidence for a direct SPP CRUD
port. These remain explicit source qualification gaps, not permanent impossibility.

Tests include independently encoded source-shaped literal empty/full inventories,
operation-1 select/create and operation-2 delete frames, all three occupied slots,
unknown/duplicate inventory revocation, signed and UTF-8 limits, full raw snapshot
and revision staleness, post-write pending, partial/read/reset behavior, and native
explicit creation/deletion with stale confirmation rejection. No device validation
is claimed by these fixtures.
