# Sony native Settings integration

## Local scope and validation

The current native-plugin route is enabled for the exact `sony-wh-1000xm6`
adapter. Other Sony models retain their existing routes. Controls additionally require
a confirmed handshake and module ownership; the page stays available during handoff.
The user tested the pre-split native page. This independently trimmed branch still
requires device revalidation; advanced protocol tests do not establish hardware acceptance.

## Information queries

Read-only Table1 queries are queued through the existing acknowledged Sony
session, after initialization and on explicit refresh. No periodic polling is
introduced. Reports on Table2 do not populate this information feature.

- `04 01` / `05 01`: reported model name.
- `04 02` / `05 02`: firmware version.
- `04 03` / `05 03`: series and numeric color.
- V2 `12 09`: display firmware, from `13 09` or `15 09` reports.
- V1 `18 00`: current codec, from `19 00` or `1B 00`.
- V2 `12 02`: current codec, from `13 02` or `15 02`.
- V1 `14 00` and V2 `12 03`: effective upscaling generation/status.

Text requires a bounded matching length, valid UTF-8 and no embedded control
characters. Unknown V2 codec values reject the report; the V1 table maps codes
outside its supported set to unsettled, including LC3. Effective DSEE status is
separate from the writable DSEE preference. Information never grants controls
or noise capabilities, and reset clears the feature.

## Artwork

Images resolve only after the reported model equals WH-1000XM6 and a known
color is available. Numeric 0x1x variants use the documented low-nibble color;
other unknown colors do not select an arbitrary image. The adapter publishes
bundled artwork IDs, and native hosts do not fetch URLs. A late color report
updates the image cache key. Catalog color codes 0 and 1 share one resource.

The bundled illustrations were generated with no reference image and recolored for the
reported palette. Vendor-server images are excluded. Generation metadata and checksums
are in [artwork sources](sony-wh1000xm6-artwork-sources.json).

## Native controls and remaining checks

The page exposes confirmed noise modes, single-headset battery, existing
assignable/voice/virtual controls, audio preferences, ambient/Clear Bass and EQ
presets, a native-themed EQ draft dialog, and multipoint confirmation/cancel.
Firmware is the final information row. Stale EQ values/definitions and expired
multipoint alerts are rejected before dispatch.

Power-off is exposed only after a complete Table1 function list advertises
V1 function 0x21 or V2 function 0x23. A native confirmation is required; the
current definition is checked again after confirmation. The ACK queue must be
idle, so a rejected busy request cannot execute unexpectedly later. No power-off
command has been tested on the physical headphones during development.

Remaining work includes final split-branch hardware validation: popup navigation,
ANC/ambient, EQ, multipoint, LE Audio transitions and companion handoff. The pre-split
page feedback does not validate these new build artifacts or every advanced control.

### LE Audio 切换（本地实现，尚未实机验证）

原生页面根据已确认的 HBS 能力、地址、可用性和设置值显示开关。用户确认后，
运行时检查 Android Profile，查询新的配对历史，再订阅耳机确认提示并发送设置。
只有实际设置通知才能确认设置；普通查询、流媒体状态或已有配对记录不代表切换完成。

页面显示检查平台、查询配对、等待确认、设置确认或失败状态。等待时保持灰色目标值，
不把设置确认写成连接成功。耳机提示包含的受限功能会显示在确认入口；回复必须匹配
当前会话内提示的序号、内容和 30 秒有效期。通道忙时拒绝操作，不延后发送被拒绝的命令。
切换前明确提示可能断连及系统蓝牙配对/重连步骤，不自动删除任何配对。

### XM6 GATT 控制路径（尚未实机验证）

XM6 声明带初始化准备和分片写入的 HPC GATT 端点。SonyPods 的探测逻辑允许
MC 缺席时通过 HPC 查询 Table2，因此该路径复用现有单通道帧与 ACK 处理。
普通连接保留 RFCOMM 顺序；明确处于 LE 音频路径时优先 GATT。

A2DP 与 LE Profile 共同决定会话存续。地址关联只取自耳机的合法协议报告，禁止用
名称或 MAC 偏移推断。已配对 Classic 设备继续作为详情页身份，控制通道可使用关联的
LE 地址。迁移时等待旧私有通道关闭；取消配对时清除关联。启动 Profile 快照只补漏，
实际连接事件优先。首次仅配对 LE、双 Profile 事件交错、别名详情入口及重连均需实测。

## Speak-to-Chat 扩展参数（本地代码验证）

对照 SonyPods 的 `SonyTandemV2Table1Protocol.buildSetSpeakToChatExtParam`
与 `parseSystemRetExtendedParam`，补充说话检测灵敏度和对话结束后的恢复速度。
仅在 V2 功能发现选中 SYSTEM type `0x02` 或 `0x0c` 且收到完整参数时显示。
`FA` 查询，`FB/FD` 回报，`FC` 写入；type `0x02` 额外携带人声聚焦位，
修改两项选项时原样保留该位。灵敏度限定 0–2，结束时间档位限定 0–3；
不将这些档位自行解释为未经验证的秒数。

写入要求原参数快照、类型和当前可用性仍匹配；排队沿用现有 Sony ACK 路径，
写后查询参数，以设备回报更新界面。单元测试覆盖两种布局、畸形回报、
未声明功能、过时快照、序列化和原生控件回调。尚未进行 XM6 实机写入验证。

## Quick Access 服务分配（本地代码验证）

新详情页按耳机回报的操作显示服务选择项。沿用 SonyPods 的 Sound Connect SAR
服务编号目录，并保留耳机额外报告的地区或新服务编号。服务分配与播放服务本身
分开：本次接入耳机设置，尚未验证对应应用的实际快捷播放链路。

V2 Table1 功能 `FD` 声明 SYSTEM inquiry `0D`。`F0/F1` 为能力，
`F2/F3/F5` 为可用性，`F6/F7/F9` 为完整服务编号向量；`F8` 写入整个向量。
必须收到完整能力、同长度当前向量和可用状态；不以默认值补齐缺少的回报。
请求绑定能力和原始向量，修改一个槽位时保留所有其他编号；写后沿用 ACK 队列查询。

来源：SonyPods `SonyTandemV2Table1Protocol` 的 Quick Access 构造/解析方法、
`QuickAccessServiceCatalog` 与 `SonyHeadphoneRepository.setQuickAccessFunction`。
验证覆盖截断、重复动作、原始编号保留、能力撤回、过时快照、实际 Adapter
Table1/2 路由、忙队列拒绝、会话清理、IPC 序列化和原生选项回调。
WH-1000XM6 实机写入与服务触发待验证。

## 双设备管理（本地代码验证）

WH-1000XM6 的 V2 会话查询 Table2 功能列表。功能 `30/32` 分别选择普通设备列表
和带 Bluetooth Class of Device 的列表（inquiry `00/02`）；未解析的新类型不会推断支持。
这些字段与 Table1 的“同时连接两台设备”总开关保持独立。

- `30/31` 查询/回报容量；`32/33/35` 回报可用性与配对模式。
- `36/37/39` 回报完整设备表：17 字节地址、连接序号、可选 3 字节设备类别、
  UTF-8 名称长度及名称；末尾为播放权序号。缺失尾字段、重复地址或连接序号均拒绝。
- `3C` 携带 inquiry、连接/断开/忘记操作及 ASCII 地址；仅允许当前完整列表中的设备。
- `34` 控制配对模式，保留官方命令要求的末尾 `00`。
- 功能 `31` 的 inquiry `01` 控制固定播放设备；功能 `34` 的 inquiry `03`
  控制接续播放，后者 UI 开启对应线上的 `01`，不能套普通 OnOff 极性。

新详情页提供回报支持的选项。连接/断开/忘记、播放设备切换与配对模式带确认，
确认后再次校验完整状态快照、会话控制权和双设备总开关。没有新增自动配对、
手机端删除配对操作或后台轮询。所有命令沿用现有 ACK 队列，忙时拒绝而不延迟执行。
设置由设备回报更新，不以命令已发送宣称连接成功。

来源：SonyPods `SonyTandemV2Table2Protocol`、`SonyTandemHeadphoneAdapter`
和 `SonyHeadphoneRepository` 的 peripheral/multipoint 实现。
测试覆盖完整帧边界、UTF-8 名称、类别字段、Table1/2 隔离、真实 Adapter 发包、
忙队列、状态过期、撤回与重置，以及确认窗口期间的状态变更。
尚未验证 WH-1000XM6 的实际连接切换；协议回报的播放权不等同于实时音频播放状态。

## 自动环境声与系统音频控件

自动环境声仅从 V2 功能 `6D` 对应的 NCASM inquiry `19` 完整回报开放。
新页面提供开关和标准/高/低灵敏度。按 SonyPods 的源实现，调整该设置切换到
环境声模式，改变灵敏度时保持自动调节开启；保留人声模式与最新环境声强度。
若同时收到旧普通布局与 `19` 布局，使用携带自动调节字段的完整回报。
`70/71` 的 SENSE 功能不能据此推断 NCASM 自动环境声支持。

请求校验原始参数快照。自动调节已经开启时，忽略耳机自主改变的强度字段，
写入时保留最新值；模式、人声或灵敏度变化仍使旧请求失效。状态等真实回报更新。
协议与原生控件测试已覆盖这些情况，XM6 实机行为待验证。

Sony 原生页清理偏好时保留系统已有的 `ldac_container` 及其
`ldac_pre`、`le_audio_pre`、`codec_claimer` 子项。控件实例、可用状态、
原生监听器和后续系统更新流程保持原样；模块不创建或推断 LDAC/LE 支持。
这修正了此前整页 `removeAll` 连带移除手机端音频选项的问题。
也保留暂时为空的原生容器，供原生 Profile 回调稍后添加已确认的控件。
该行为由宿主偏好实例测试验证，实际 ROM 是否为 XM6 创建对应选项仍待实机检查。
