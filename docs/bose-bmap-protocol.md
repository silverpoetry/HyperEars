# Bose BMAP 家族适配

## 1. 设计边界

HyperEars 只在 Android 已确认设备属于标准耳机、且名称、Bose OUI 或 Bose 专属
`FEBE` 服务 UUID 命中时创建 Bose 家族会话。公共的 accessory-side UUID
不参与品牌判型。最终型号不依赖蓝牙名称，而由只读 BMAP
`ProductInfo.ProductIdVariants [0.3]` 响应确认。

产品 ID 未登记，或已登记但没有静态控制画像时，家族协议会依次读取 AudioModes、
CNC、ANR 状态。只有某个精确的 STATUS 响应通过对应 Codec 校验后，才把会话升级到
该线协议的家族能力 Adapter；无有效响应时仍只提供 BMAP 电量和 MiLink 流转。

## 2. 传输与初始化

BMAP 帧直接承载于 RFCOMM：

```text
[functionBlock, function, flags, payloadLength, payload...]
```

`flags` 低四位为操作符：`GET=1`、`SETGET=2`、`STATUS=3`、`START=5`。
端点按以下顺序尝试：

1. RFCOMM channel 8（本地 `prince` 及公开 QC35/35 II 实现）；
2. 标准 SPP UUID；
3. 公共 accessory-side RFCOMM UUID `00000000-deca-fade-deca-deafdecacaff`
   （仅作传输候选，不作 Bose 身份）；
4. RFCOMM channel 2（较新 BMAP 设备）。

RFCOMM `connect()` 成功不代表业务端点正确：部分 UUID 可以建立 Socket 却不返回
BMAP。每个候选端点必须在 2.5 秒内对 `[0.3]` 返回合法且接受的协议握手，否则关闭
该 Socket 并尝试下一端点。只有验证成功的候选才发布“通道已连接”，避免假连接使
卡片永久退化为音量。

每个会话先发送 `GET [0.1]`。QC35/35 II 需要该初始化请求才会响应后续 BMAP
命令；其他已知设备将其作为普通只读查询处理。随后读取产品 ID `[0.3]` 和电量
`[2.2]`，具体型号确认后才读取或写入对应噪声控制功能块。

## 3. 产品目录与能力分级

产品 ID 与代号来自公开的 Bose BMAP 产品目录；控制能力按证据单独分级。

| 产品 | 代号 / ID | 形态 | HyperEars 控制画像 | 证据 |
|---|---|---|---|---|
| QuietComfort 35 | `wolfcastle/0x400C` | 头戴 | ANR：高/风噪/关闭 | 公开实机实现 |
| QuietComfort 35 II | `baywolf/0x4020` | 头戴 | ANR：高/风噪/关闭 | 公开实机实现 |
| Noise Cancelling Headphones 700 | `goodyear/0x4024` | 头戴 | CNC：降噪/通透端点/关闭 | 公开实机实现 |
| QuietComfort 45 | `duran/0x4039` | 头戴 | AudioModes：安静/感知 | 公开实机实现 |
| QuietComfort Ultra Headphones | `lonestarr/0x4066` | 头戴 | AudioModes：安静/感知 | 同代协议盲配 |
| QuietComfort Headphones | `prince/0x4075` | 头戴 | AudioModes：安静/感知/已发现风噪预设 | HyperEars 实机验证 |
| QuietComfort Ultra Headphones (2nd Gen) | `wolverine/0x4082` | 头戴 | AudioModes：安静/感知 | 公开实机实现 |
| QuietComfort Earbuds | `lando/0x402F` | TWS | AudioModes：安静/感知 | 公开实机实现 |
| QuietComfort Earbuds II | `smalls/0x4064` | TWS | AudioModes：安静/感知 | 同代协议盲配 |
| QuietComfort Ultra Earbuds | `scotty/0x4072` | TWS | AudioModes：安静/感知 | 同代协议盲配 |
| QuietComfort Ultra Earbuds (2nd Gen) | `edith/0x4062` | TWS | AudioModes：安静/感知 | 公开配置盲配 |

以下产品没有预置写画像，只登记产品 ID、形态和通用 BMAP 电量；若设备实际响应第 4
节所列只读能力探测，则运行时开放该协议确认支持的模式：Hearphones、
Hearphones II、ProFlight、SoundSport、SoundSport Pulse、QuietControl 30、SoundSport
Free、Sport Earbuds、Sport Open Earbuds、Ultra Open Earbuds。

“公开实机实现”表示存在可检查的第三方开源实现，不表示 HyperEars 已持有对应设备；
“同代协议盲配”需要社区实机继续验证。

## 4. 三种噪声控制画像

未知画像只发送以下三个 GET：`[31.3]`、`[1.5]`、`[1.6]`。ERROR、无响应和格式不符
均不产生能力；首次合法 STATUS 决定当前会话画像，之后写入和回读都只走该画像。
探测不修改设备配置，也不增加轮询。

### 4.1 QC35 ANR `[1.6]`

```text
GET:    01 06 01 00
SETGET: 01 06 02 01 <level>
STATUS: 01 06 03 02 <level> <capabilities>
```

公开实现给出的值为 `0=关闭`、`1=高降噪`、`2=风噪`、`3=低降噪`。MiLink
没有“低降噪”语义，HyperEars 不把它误报成通透；当前只发布高降噪、风噪和关闭。
具体卡片呈现用 MiLink 自己的 ANC item 把不适用的“通透”槽替换为“抗风噪”。

### 4.2 NC700 CNC `[1.5]`

```text
GET:    01 05 01 00
SETGET: 01 05 02 02 <10-level> <enabled>
STATUS: 01 05 03 03 <steps> <10-level> <enabled>
```

HyperEars 只映射系统卡片能准确表达的三个端点：最大降噪、完全感知和关闭。NC700
从关闭重新启用时会先恢复最大降噪；仅当当前状态明确为关闭、目标为完全感知时，
协议会按公开实现发送第二个相同 SETGET，使最终级别落到感知端点。其他切换只写一次。

### 4.3 AudioModes `[31.3]` / `[31.6]`

```text
GET current:   1F 03 01 00
START switch:  1F 03 05 02 <modeIndex> <voicePrompt>
START configs: 1F 06 05 00
```

已知 Quiet/Aware 索引分别为 `0/1`。没有可靠关闭命令的产品只发布这两种状态；系统
原生三项卡片中的“关闭”项保留显示但设为禁用，不会发送命令。`prince/0x4075` 额外读取 ModeConfig，并只切换设备实际
返回且 `wind=true` 的预设，不修改用户模式参数。不同代 ModeConfig 的字段偏移由
具体 Adapter 内嵌的 `BoseWireConfig` 提供，Codec 不写死成一个型号布局。

卡片按实际原生布局选择适配：旧版 `anc_card` / `HeadsetControlAncItemView`，以及新版
`anc_select_card` / `HeadsetSelectCardView` / `HeadsetSelectItemView`。`0x4075` 的抗风噪
替换“关闭”槽，QC35 系列替换“通透”槽；两态型号保留并禁用“关闭”。原生选项的尺寸、
边距和图标样式复用当前布局，解绑后恢复原控件。布局与模式映射有回归测试，新版 Bose
卡片的实际渲染与点击仍待实机复核。

## 5. 电量 `[2.2]`

```text
GET: 02 02 01 00
```

STATUS payload 按四字节组件重复：

```text
[percent, remainingMinutesHi, remainingMinutesLo, componentId]
```

`componentId` 为 `0=整机`、`1=左`、`2=右`、`3=盒`、`4=系统`。头戴型号发布整机
电量；支持组件分组的入耳式产品优先发布真实左右耳和充电盒，不把整机值伪装成盒电量。

## 6. 运行时结构

- `BoseEarbudAdapter`：无扫描家族初筛、Bose 身份证据、端点顺序、当前能力和 BMAP 会话所有权。
- `BoseBmapModelRegistry`：产品 ID 到具体 Adapter 线配置的内部目录。
- `BoseCapabilityConfigRegistry`：型号画像缺失时，由合法 STATUS 选择的只读能力配置；
  分别保留头戴/TWS 形态。
- 具体型号 Adapter：声明产品 ID、形态、能力、`BoseWireConfig` 和可选卡片呈现。
- `BoseBmapProtocolSession`：同一字节流上的增量解码、请求状态、电量和模式回读；
  它只产生产品 ID 与协议证据，不自行选择 Adapter。
- `BoseBmapWireCodec`：纯 BMAP 分帧，以及 Product、Battery、ANR、CNC、AudioModes
  的无状态编解码。

家族 Adapter 收到产品 ID 后，由自身映射并返回 `Replace`。设备会话原子替换为具体
Adapter，同时复用已建立的 RFCOMM 与原 `ProtocolSession`；产品未知时，只有合法只读
STATUS 才会形成带对应控制方言的新 Adapter。UI 与 MiLink 只接收替换后的完整快照。

未知产品 ID 不会伪装成具体型号；只有协议能力探测成功才得到对应写命令。电量 `[2.2]`
始终独立解析，因此噪声控制探测失败不会使电量退化。协议响应、设备会话和 UI 状态仍
按蓝牙地址隔离，不增加扫描、轮询或额外常驻服务。

## 7. 研究来源

- HyperEars 对 `prince/0x4075` 的实机抓包和 Bose Music China 12.4.2 APK 行为核对。
- [aaronsb/bosectl](https://github.com/aaronsb/bosectl)：BMAP 产品目录、QC35/35 II
  ANR、QC Ultra 二代 AudioModes 和不同 RFCOMM 通道的公开实机实现。
- [danielgjackson/noisecancel](https://github.com/danielgjackson/noisecancel)：NC700、
  QC35、QC45 与 QC Earbuds 的公开 Android 控制帧实现。

HyperEars 独立实现互操作所需的协议事实，不分发上述项目的程序、抓包或品牌资源。

## 日常设置：来源限定与会话确认

本切片依据 `aaronsb/bosectl` 固定提交
`70570b29c1d3c9ba775b08a29eaea72385de27b5` 的 Python 设备实现与解析器，以及
`danielgjackson/noisecancel` 固定提交 `0391b614fffdf20ca5f3cdd42413dbae194e0740`
的 NC700／QC35 协议记录。两者为 MIT，来源设备实测声明不等于本版 HyperEars 实测。

型号画像只允许读取对应字段；产品 ID 接受后发送有限 GET，实际合法 STATUS 才逐项
开放控制。GET、SETGET 回显、ERROR、RESULT 和 PROCESSING 不作为状态证据。
全部新增写入采用 SETGET，随后一次 GET 回读；不乐观修改确认值，不后台轮询。

- 三段 EQ `[1.7]`：QC45 `4039`、QuietComfort Headphones `4075`、初代 QC Earbuds
  `402F`、Ultra Headphones 2 `4082`、Ultra Earbuds 2 `4062`、Ultra Open `4068`。
  STATUS 是四字节重复记录 `[minS8,maxS8,currentS8,bandId]`，仅支持来源三段 ID
  `0/1/2`，拒绝截断、重复 ID 和越界值；SETGET 为 `[valueS8,bandId]`。
  成功空列表或减少频段会撤销旧频段控制。请求包含原频段 ID 和上下限，界面及会话
  分别拒绝定义变化后的旧滑块请求。
- 语音提示 `[1.3]`：上述六款及 QC35／35 II `400C/4020`。仅控制 bit5 开关，
  每次写入从最新设备回报保留语言低五位及上两位未知信息。不凭全局语言表宣称
  当前设备已安装某种语言，不新增语言切换。
- 双设备 `[1.10]`：QC45、初代 QC Earbuds、Ultra 二代头戴／耳塞及 Ultra Open。
  读取一个状态字节的 bit1；写入语义为独立 `0/1`，不是把原位图原样写回。
  QC45 来源只有 GET 的设备实测声明，SETGET 来源实现存在，本版仍待设备验证。
- 自动播放／暂停 `[1.24]`：只在 `4082/4062` 查询和开放。严格 `0/1` 响应，
  不把 QC45、QC35 或初代 QC Earbuds 推断为支持。
- 直接 CNC 等级 `[1.5]`：只在 NC700 `4024` 和初代 QC Earbuds `402F` 开放。
  STATUS 必须是 `[11,rawLevel0..10,enabled0/1]`，SETGET 为 `[rawLevel,1]`。
  UI 强度为 `10-rawLevel`；调节会开启降噪。NC700 从关闭恢复到非零 rawLevel 时，
  根据来源默认等级行为重复一次同一命令，最终仍以回读为准。
- QC35／35 II 的 ANR `[1.6]`：独立保留关闭 `0`、高 `1`、抗风噪 `2`、低 `3`。
  原三态投影把低档显示为 ANC 大类，高级详情保持低档可见和可选。

QC45 和 `prince` 的 `[1.5]` 可读不代表可写，本轮不开放直接 CNC 控制。
QC45／prince 的 ModeConfig 为 47 字节 STATUS／39 字节 SETGET，而 Ultra 二代是
48／40 字节；初代 QC Earbuds 的 44 字节 ModeConfig 没有可用写入格式。
现有型号模式切换和原生槽位映射保留，不在本切片新建或编辑模式，不把空字段补零
后写回整个配置，也不把 `[31.10]` 的空间／风噪字段外推给其他型号。

MiLink 高级详情沿用现有原生扩展：Quiet/Aware、抗风噪替换关闭、抗风噪替换通透
三个 Bose 既有模型分别包装，保留各自槽位投影；真正三态 CNC 使用原生三态卡片。
Ultra Open 等仅有设置证据的设备使用设置载体，不凭 EQ 开放噪声能力。
详情在每次操作前检查地址、当前会话令牌、握手、连接和控制所有权；设备重置、错误
身份、页面脱离和 App 交接清除或关闭相应状态，超时仅提示未确认，不自动重试。

### Sidetone and automatic call answering

Pinned bosectl `70570b29` `python/pybmap/devices/parsers.py:177–182,232–235`
and `constants.py:49–50` define sidetone at block/function `1/11`: a two-byte
STATUS reports the level in byte 1, and SET_GET sends `[1, level]`. Levels are
0 off, 1 high, 2 medium, 3 low. Executable profiles `qc35.py`, `qc45.py`,
`qc_earbuds.py`, and `qc_ultra2.py` qualify IDs `4020`, `4039`, `402F`, `4082`.
This does not extend to QC35 I `400C`, Ultra 2 earbuds `4062`, or similarly named models.

Automatic call answering uses `1/27` with one boolean byte, only for `4082`
(QC Ultra 2 headphones); QC45 explicitly documents no support. Both controls
require a valid STATUS from the qualified product, issue one SET_GET followed
by the corresponding GET, and retain reported selection until readback.
Wrong operators, wrong payload sizes and out-of-domain values grant no controls.
Existing product-change/session reset removes the evidence. Native details expose
four sidetone choices and the automatic-answer toggle only after their own reports.
No phone or physical-device verification was performed for this addition.

## Report-advertised button actions

The next daily slice uses bosectl `70570b2` (MIT), `devices/parsers.py:92–160`,
`constants.py:62–85`, and `connection.py:321,535–552`. Explicit device modules
qc35/qc45/qc_earbuds/qc_ultra2 declare both GET and SET_GET builders at1/9.
Only product IDs4020/4039/402F/4082 qualify; original QC35I400C, prince4075,
Ultra earbuds and other Bose identities do not inherit these controls.

A STATUS payload contains button ID, event, current action, then zero to four mask
bytes. Mask bit N advertises action N; bit0 is not a writable advertised action.
Known named actions are intersected with the reported mask. No default action list
is invented from product identity. Three-byte reports retain their current value but
revoke previous mask-based write eligibility for that button/event. Unknown reserved
button/event IDs, wrong operators, malformed lengths and other function blocks do
not grant controls. Independent slots retain their own report snapshots.

Typed SetButton pins product, button, event, current action and all reported mask
bytes. SET_GET contains only button/event/action, followed by GET1/9. A changed
mask/current value/product rejects an old request; reset clears all button reports.
Selection stays unchanged until STATUS. The original QC35 fixture
`01 09 03 07 10 04 01 06 00 00 00` independently proves VPA/current1 and the
advertised VPA/ANC mask; its ANC write is `01 09 02 03 10 04 02`.

Native Settings and existing Bose details expose finite choices only from these
reports. A current value outside writable choices remains explicitly labeled as a
device report; it is not offered as a new write option. Existing owner/address/session
checks and the captured expected slot reject stale callbacks. This addition neither
creates a transport nor changes mode profiles, spatial settings, device names or
firmware behavior. Source-based tests are not physical-device validation.

## QC Ultra 2 live spatial/wind settings

Only product4082's `qc_ultra2.py:126–130` declares executable audio_settings
read/write support. Bosectl `70570b2` (MIT), `parsers.py:398–420` defines the five-byte
AudioModes31/10 structure: CNC level0..10, automatic CNC boolean, spatial0/1/2,
wind boolean, and ANC-toggle boolean. `connection.py:442–464` implements the live
spatial operation with GET then SET_GET. Spatial0 means off,1 fixed room stage,
2 head tracking. This is separate from editing or creating a ModeConfig slot.

The independent implementation requires exactly five valid STATUS bytes after
accepted4082 identity. Typed spatial/wind requests pin product and the complete raw
snapshot. Writes replace only byte2 or3; CNC, automatic-CNC and ANC-toggle bytes are
preserved. In particular, the upstream convenience helper omits automatic-CNC when
rebuilding the payload; this port preserves its reported value instead. Both writes
query31/10 again and do not publish intent as state. A session guard rejects another
whole-body edit before a valid31/10 STATUS or reset, avoiding replay of stale sibling
fields after a local write. Native Settings and details include an explicit refresh action after a missing reply;
it uses Standard Refresh and queries31/10 to restore writable evidence; no retry loop or timer is added.
Other products, including similarly
named Ultra earbuds, do not acquire this control. Snapshot changes and reset reject
old requests; the feature does not itself advertise a noise-mode capability.

## Device-reported name and rename

Products4020/4039 explicitly declare product_name builders in qc35.py/qc45.py.
`connection.py:29,482–488` limits writes to31 UTF-8 bytes and sends the name alone
through SET_GET1/2. `parsers.py:55–57` reads a flag followed by UTF-8 name bytes.
Its independent captured fixture is `00 46 61 72 67 6F` (flag0, name Fargo).
GET1/2 supplies authoritative readback; the flag is retained in expected snapshot
identity, not prepended to the write. A new flag/name/product report invalidates an
old draft even when its target text would still be valid.

STATUS names use the BMAP payload bound (flag plus at most254 bytes), not the write
limit; a long existing device name can be renamed to a shorter valid name. Writes
reject malformed UTF-8, unpaired UTF-16 surrogates, empty/blank names, control
characters and more than31 encoded bytes, without truncating multibyte text.
Native Settings uses the host EditTextPreference with report-only value and summary;
missing host support falls back to a read-only name row. The existing Bose native
details also contains an explicit draft and Save-to-headphones action; current firmware
name remains unchanged until STATUS. Input validation explains the byte limit.
Session/owner/address loss closes the editor through the existing lifecycle.
No Android Bluetooth alias is modified or treated as device confirmation. These
write capabilities are not inferred for other product_name readers or generic models.

Protocol, adapter and native UI fixtures cover independent golden bytes, preserved
unmodified fields, stale full snapshots, exact product exclusion, UTF-8 boundaries,
readback-only display and lifecycle cleanup. Physical-device revalidation remains
separate from source and unit verification.
