# OPPO Enco 协议盲适配记录

## 1. 资料边界

本适配依据开源项目 `1812z/OppoPods` 中公开的协议事实重新实现，尚未在当前
HyperEars 测试设备上逐型号抓包确认。因此这里区分两类结论：

- 已由参考实现明确给出的帧格式、命令、UUID 和型号例外；
- HyperEars 为接入统一状态模型所做的保守映射。

游戏、低延迟、双设备、佩戴自动播放与均衡器已按固定开源实现接入原生卡片高级详情。
每项功能必须由当前会话的合法响应独立确认；目录功能标志不开放控制。空间音频、
长帧／分片见第 10 节；校准和黄金听感仍属于其他实现切片。

## 2. 识别与端点

当前身份路径依据 `Leaf-lsgtky/OppoPods` 固定提交
`929d813f1b82d5b09dd39a84b4f18744e8ef7e64` 的 `ProductIdParser` 和
`device_models.json`。目录包含 141 条身份记录及子型号销售别名，不代表 141 款实测支持。

1. 只处理 Android 已确认是耳机且不是小米原生耳机的设备；
2. 名称按完整归一化别名匹配，保留 `+` 后缀；`X3s` 不落入 `X3`，`Air5 Pro` 不落入 `Air5`；
3. 118 条明确声明 SPP 的耳机记录可选择候选 Adapter；另 23 条未声明或关闭 SPP、音箱
   记录保留在目录中，不因导入目录主动探测私有通道；
4. 同名对应多个产品 ID（例如 `OnePlus Buds`）时留在家族候选，等待设备身份；
5. 其他 OPPO/Enco 名称保留家族探测，其他品牌的未知名称保持标准路径；
6. 发送 `0x0103`，仅接受 `0x8103` 的四字节载荷：成功状态 `00` 后跟三字节小端 ID。
   已知且允许的 ID 可在原通道细化到具体 Adapter，未知 ID 和错误响应不替换具体身份。

产品 ID 仅确认协议身份，不开放电量、降噪、空间音频等能力。目录没有导入厂商功能标志
或图片。更换 Air2 Pro / 通用模式编码时清除旧噪声证据，后续合法模式响应重新确认；
同一批字节中的后续帧使用新编码。连接重置清除协议确认与能力，保留具体型号为候选。

私有通道为 Bluetooth Classic RFCOMM：

```text
0000079a-d102-11e1-9b23-00025b00a5a5
```

不扫描 BLE，不接管 A2DP/HFP，也不引入额外常驻服务。

## 3. 帧格式

多字节字段均为小端：

```text
AA | TotalLen | 00 00 | Command(2) | Sequence | PayloadLen(2) | Payload
```

- `TotalLen = 7 + PayloadLen`
- 默认 `Sequence = F0`
- 查询产品 ID：命令 `0x0103`；响应 `0x8103`
- 查询电量：命令 `0x0106`
- 电量响应：命令 `0x8106`
- 查询降噪：命令 `0x010C`，载荷 `01 01`
- 设置降噪：命令 `0x0404`
- 降噪响应：命令 `0x810C`
- 主动状态上报：命令 `0x0204`

`OppoWireCodec.Decoder` 按 `AA + TotalLen` 做流式分帧，支持拆包、粘包和前导
噪声恢复。WireCodec 只输出原始字段，不解释具体型号的模式语义。

## 4. 电量

查询响应载荷由若干二元组组成：

```text
[Component, RawValue] ...
```

- `Component = 1/2/3`：左耳、右耳、充电盒
- `percent = RawValue & 0x7F`
- `charging = RawValue & 0x80 != 0`

主动电量上报使用 `0x0204`，载荷为：

```text
01 | Count | [Component, RawValue] * Count
```

主动报告可能只包含部分组件。会话协议在设备范围内合并增量，避免一个单耳
更新把另一个耳或充电盒电量清空。

## 5. 降噪与型号线配置

设置载荷以 `01 01` 开头。通用 OPPO Adapter 的 `OppoWireConfig.STANDARD` 使用：

| 统一状态 | 写入载荷尾部 |
|---|---|
| 关闭 | `01` |
| 降噪 | `02` |
| 通透 | `04` |

`OPPO Enco Air2 Pro` 是公开实现中唯一登记的兼容例外：

| 统一状态 | 写入载荷尾部 |
|---|---|
| 关闭 | `02` |
| 降噪 | `01` |
| 通透 | `04` |

这个差异声明在 `OppoEncoAir2ProAdapter.wireConfig`，而不是写死在
WireCodec 或 MiLink Hook 中。

参考实现还识别智能、轻度、中度、深度和自适应降噪。HyperEars 当前统一领域
只有 `ANC/OFF/TRANSPARENCY/WIND`，因此：

- 读到智能或强度档位时投影为 `ANC`，保证卡片状态与耳机大类一致；
- 读到 Free4 自适应状态时同样显示为 `ANC`；
- 不把自适应冒充 `WIND`，也不暴露没有对应领域语义的写入口。

## 6. 状态同步

建立通道后依次执行：

1. 查询产品 ID（`0x0103`）；
2. 查询支持的通知 ID（`0x0200`）；
3. 查询电量；
4. 查询当前降噪状态；
5. 查询四项开关状态、当前均衡器和设备 EQ 列表；
6. 收到 `0x8200` 后过滤调试 ID，并通过 `0x0205` 一次性订阅其余通知。

这不是轮询。控制写入后仅做一次 `0x010C` 权威回读，日常电量和模式变化由
耳机 `0x0204` 主动报告驱动。

## 7. Adapter 层级

```text
StandardEarbudAdapter
  └─ OppoEarbudAdapter (保守家族入口，等待协议证据)
       ├─ OppoEncoAir2ProAdapter (兼容 ANC 编码)
       ├─ OppoEncoFree4Adapter
       ├─ OppoEncoX3Adapter
       ├─ OppoEncoAir5Adapter
       └─ OppoCatalogEarbudAdapter (目录精确候选／产品 ID 细化)
```

四个具体型号保留独立 ID，但名称只决定候选线配置。所有 OPPO Adapter 初始都只发布
系统整机电量与流转能力；通知支持响应只确认协议并建立订阅，合法电量响应开放私有组件
电量，合法 ANC 状态响应才开放降噪、关闭和通透。当前具体型号除 Air2 Pro 的编码例外外，
均复用家族 `ProtocolSession`。

## 8. MiLink 边界

OPPO 设备复用 HyperEars 已有的 TWS 官方载体 ID 和 MiLink 原生三态卡片。
本次适配没有新增：

- 混淆类/方法 Hook；
- View ID 或卡片布局 Hook；
- 设置页注入；
- 后台扫描或常驻轮询任务。高级详情使用随弹层关闭清理的有界确认超时。

资料来源：

- <https://github.com/1812z/OppoPods>

- <https://github.com/Leaf-lsgtky/OppoPods/tree/929d813f1b82d5b09dd39a84b4f18744e8ef7e64>

## 9. 日常开关和耳机 EQ

依据 Leaf 固定提交 `929d813f` 的 `Packets.kt`、`CapabilityProfileFactory.kt`、
`DeviceCapabilities.kt` 和 `EqualizerPage.kt`；这些是来源实现证据，尚未逐型号实测。

- `0x010D` 查询载荷为 `[count, feature IDs...]`；仅接受 `0x810D` 的
  `[00, count, (feature, value)...]` 完整结构，已知布尔字段只允许 `0/1`。
  重复 ID、错误状态、截断和多余字节整帧拒绝，不沿用来源实现的任意字节扫描兜底。
- `0x0403` 写入 `[feature, 0/1]`。四项独立 ID 为佩戴自动播放／暂停 `04`、
  低延迟 `06`、双设备 `11`、游戏模式 `28`。不会把两个游戏相关 ID 合并，也不把
  开关配置误称为当前实际佩戴状态。每项控制后重新查询同一组四字段，成功响应作为完整快照替换开关状态；
  缺失字段或成功空快照撤销旧控制，畸形帧不更改证据。
- `0x010F` 查询当前 EQ；`0x810F` 和 `0x0504` 严格接受 `[00, eqId]`。
  `0x0122 [01,05]` 查询完整 EQ 条目；`0x8122` 以 `[00,count]` 开头，逐条读取
  选择标记、增益上下限、ID、UTF-8 名称及频率／有符号增益。列表须完全解析，拒绝
  重复 ID、多选、越界增益、无效 UTF-8、控制字符和尾随数据。
- `0x0406 [eqId]` 只允许选择当前设备列表回报的 ID。单独回报当前 ID 不开放任意
  预设选择；没有把静态目录中的 EQ 标志或预设选项当成设备支持证明。
- 已有自定义 EQ 编辑使用 `0x0418` 的更新动作 `02`。名称、ID、频率和上下限沿用
  当前设备条目，仅修改范围内的增益。74 份来源型号的内置 EQ ID 集合并所有固件条件与嵌套变体，仅用于排除内置
  槽位：须获得对应产品 ID 和合法条目回报，且条目不属于内置 ID，才开放编辑。
  未知型号、空频段、超范围参数和无法排除的内置槽位不开放编辑。
- 本切片不创建或删除 EQ。更新后查询 `0x0122`；写入 ACK 不确认状态，失败、超时
  和旧会话回报不改为成功。完整空列表会撤销旧选项及编辑资格。

默认自定义更新载荷仍限制为 120 字节；本次连接获得明确的 canonical 长帧 EQ 列表
和有效帧预算响应后，才开放至结构上限 357 字节（名称最多 255 字节、32 个频段）。
列表读取支持长帧及完整分片；写入资格、预算和会话证据见下一节。

MiLink 原生噪声卡提供高级详情入口；仅有设置证据时复用现有设置载体，不伪造噪声
能力。开关和已选 EQ 只显示设备确认值；滑块是明确标记的待应用草稿。详情页检查
当前地址、会话令牌、握手、通道和控制所有权；断连、交接、解绑和宿主 View 分离
释放观察者与超时回调。初始化只做一次有限查询，控制后做一次回读，没有后台轮询。

## 10. 长度分歧、分片和写入预算

来源固定为 Leaf `929d813f1b82d5b09dd39a84b4f18744e8ef7e64` 的 `Packets.kt`
和 `docs/HeyMelody_Official_App_Protocol_Findings.md`，以及 GPL-3.0 的
[QuickBuds `6e069325984bab50c5bd331ced8805923c1db410`](https://github.com/spizganed/QuickBuds/tree/6e069325984bab50c5bd331ced8805923c1db410)。
QuickBuds 的 `docs/PROTOCOL.md`、`protocol/Wire.kt` 和 `testdata/protocol_cases.json`
提供相互对应的语法与固定用例；这不等同于 HyperEars 已完成设备验证。

- 完整 OPOv1 帧为 `AA | bodyLength LEB128 | 00 00 | cmd LE16 | seq | payloadLength LE16 | payload`。
  `bodyLength = 7 + payloadLength`，不包括 `AA` 和长度字段自身，没有尾部校验和。
- `frame-over127-01` 明确标记为 `CAPTURE`，引用来源设备日志第 259 行，其帧头为
  `AA B5 01 00 00 1F 01 D5 AE 00`：`B5 01` 为 181，`AE 00` 为 174，差恰好为 7。
  仓库回归测试只保存真实帧头，载荷替换为合成零字节，没有复制听感数据。
  `frame-over127-02` 的 `AA 83 01 ... 7C 00` 是来源 `TEST-LITERAL`，不是抓包。
- 1812z 的旧 API 使用单字节原值长度；原 `packet()` 和最多 248 字节载荷的字节布局保留。
  Star 文档提出的“长长度先减一、解码再加一”不符合以上抓包，且在 128 边界自相矛盾，
  不实现为猜测方言。解码通过 link 保留字段及冗余内层长度严格区分旧原值与 LEB128；
  小于 128 的公共字节布局不证明长帧支持。
- 分片 link control 为 `01` 首片、`02` 中片、`03` 末片，其后保留字节 `00`，
  再跟从 `00` 开始递增的单字节 FSN；body 长度为 `3 + fragmentDataSize`。
  拼接对象为完整内层 `cmd LE16 | seq | payloadLength LE16 | payload`。
  来源 `frame-slice-01` 为合成测试而非设备抓包。首片替换旧拼接，缺片、重复、乱序、
  FSN 回绕、超长和内层长度不符丢弃该消息；后续独立合法帧继续解析。
- 待解码物理帧上限沿用 Leaf 的 12388 字节防护，拼接内层上限为 65540 字节，最多 256 片。
  输入逐字节进入固定大小缓冲区，单次巨大输入不扩大驻留缓冲；断连 reset 同时清除分片。
  未知 link 标志与非零保留字段不按已知格式猜测解析。
- `0x0107` 为无载荷帧预算查询，严格接受 `0x8107 [00, budget LE16]`；仅接收本地编码器
  能处理的 10..12388。初始化增加一次查询，不新增后台轮询。未回报时不假设 MTU 或 660 默认值。
  只有合法完整 EQ 列表至少一个物理帧明确使用两字节 canonical 长度，且收到有效预算，才选择
  长 EQ 写入；全由短片组成的长消息本身不授予长长度证据。按回报预算产生完整帧或分片，
  不先发送大包试探。合法预算也限制旧短更新；畸形预算不覆盖已有确认值。
- 型号名称不选择方言。新增证据仅属于当前协议会话；reset 后重新取得，发送与 ACK 不改变
  EQ 确认值。更长列表沿用原完整结构验证、当前定义竞态校验、内置槽位排除和原生详情编辑。

## Product-indexed ANC and transparency selectors

Leaf-lsgtky/OppoPods `929d813f1b82d5b09dd39a84b4f18744e8ef7e64`
`DeviceModelRegistry.kt:358–465` and `CapabilityProfileFactory.kt:62–106` define the
per-product `noiseReductionMode` tree and the parent/child write distinction. QuickBuds
`6e069325984bab50c5bd331ced8805923c1db410`, `protocol/Commands.kt`, `Anc.kt` and
`Decode.kt` independently establish the bitmap command and distinguish actual current-mode
reports from gesture-cycle mask echoes. Both upstreams publish GNU GPLv3 license metadata.
Only protocol mappings are adapted; no vendor graphics are included.

The catalog contains 43 product tables and 304 report slots with known semantic names.
Six child slots using modeType 11/12/13 on products `06EC10` and `067814` remain excluded:
the reviewed implementation does not establish their meaning. Product identity and a complete
current-mode report are both required; the catalog alone grants no control capability.
Run `python tools/verify-oppo-noise-catalog.py PATH_TO_PINNED_device_models.json` to compare
every imported slot, writable alias and conditional gate against the full source.

Writes use `0x0404` payload `01 01` followed by the minimal little-endian bitmap with one
`protocolIndex` bit set. For example, Buds 4 `065414` sends off at bit 0, transparency at bit 2,
deep/medium/light/smart ANC at bits 4/5/6/7 and adaptive at bit 11. Reports can name off at
bit 3 and transparency at bit 8; those same-name child slots are report aliases, not write choices.
Older product tables such as Air2 Pro `063810` use their own parent indices without extra swapping.

The advanced parser accepts `0x810C` status `00` followed by exact `01 01 <bitmap>`, or
`0x0204` exact subtype `03 01 01 <bitmap>`. It requires one set bit in one or two bitmap bytes.
Gesture-cycle echoes `03 02 01 ...`, fit results, error statuses, multi-bit masks and trailing data
cannot enable selectors. The existing family fallback retains its historical no-status captures;
that permissive parser is not used to grant indexed controls on identified models.

The existing native OPPO details panel now displays the model's ANC levels, adaptive mode and
proven transparency variants. Four `decideByEarDevice` listening entries remain report-only
because their extra support query has not been implemented. Current reports stay authoritative;
selecting a mode writes once and reads current ANC again. Standard native mode buttons use the
model's parent indices after report qualification. Typed selection requests carry the immutable
product/table definition, so a delayed UI or IPC request cannot apply an old product's bit index.
Identity changes revoke old noise selection while retaining independently confirmed EQ/toggles;
unknown current indices revoke the selector and prevent stale indexed writes.

These are source-backed candidate controls with automated fixtures. No additional physical-device
acceptance is claimed, and LE Audio/GoldenSound workflows are outside this slice.

## Prompt volume and legacy spatial sound

QuickBuds `6e069325984bab50c5bd331ced8805923c1db410`, protocol `Commands.kt:202–207`
and `Decode.kt:53`, provides prompt-volume query `0130`, response `8130 [00,level]`,
and write `0427 [level]`. HyperEars requires the exact successful two-byte response and
level 1..10 before enabling the native slider. Write acknowledgements do not confirm a level;
the existing session requests `0130` after writing. Invalid reports preserve prior evidence.
This query is independent of model names and requires a real response even on known products.

Leaf `929d813f`, `DeviceCapabilities.kt:121–125` and
`CapabilityProfileFactory.kt:137–142`, qualifies the legacy spatial-sound boolean only when
`spatialTypes` is nonempty and excludes type2. `OppoLegacySpatialCatalog` retains those 44
product IDs as query eligibility, never as capability evidence. After actual product identity,
the normal switch query includes `1B`; only a successful independently reported boolean enables
its native row. Writes use `0403 [1B,boolean]` and read the complete qualified switch set again.
A missing field revokes that row; switching product clears its evidence. Requests include the
expected product ID to reject old UI/IPC actions. EQ, prompt volume and other toggles are preserved.

The label is “空间音效”. This is not the separate `0422` fixed/head-tracking selector and does
not assert motion tracking or host rendering. The five products advertising type2 remain outside
this boolean path. Existing four-switch queries remain unchanged for other/unknown products.
All new controls remain source-backed candidates pending physical-device validation.
