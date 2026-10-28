# MOONDROP Pudding 协议适配

HyperEars 实现 MOONDROP Pudding 接入 MiLink 所需的最小协议子集：与 Robin 同构的严格
握手、私有电量读取与噪声模式查询、写后确认。公开资料记录的蓝牙名称为
`MOONDROP Pudding`。协议确认前电量沿用 Android 系统整机，确认后切换为私有左右耳与
充电盒电量。

## 1. 判型与传输

Adapter 只在下列名称规则之一成立时选择 Pudding：

- 规范化完整名称为 `moondroppudding`；
- 名称同时包含 `moondrop` 与 `pudding`；
- 名称同时包含“水月雨”与 `pudding`。

传输使用 Bluetooth SIG 标准 SPP UUID `00001101-0000-1000-8000-00805f9b34fb`。该 UUID 被
大量蓝牙设备共同使用，只负责建立 RFCOMM 端点，不属于水月雨身份依据，也不会让其他 SPP
耳机进入 Pudding Adapter。

## 2. 帧格式

业务帧由 8 字节头和变长参数组成：

```text
FF TT 00 LL HH CC SS OO [parameters...]
```

- `TT`：消息类型；
- `LL HH`：参数长度，小端序；
- `CC SS OO`：三字节操作标识；
- `parameters`：恰好为长度字段指定的参数。

实测捕获中部分帧在参数之后还带一个额外尾字节（耳机响应常见 `40`，手机命令见 `86`）。
该尾字节是否存在及其取值不稳定，因此所有解析只校验长度字段内的参数，**载荷外的字节
一律忽略**，不参与确认。解码器按长度处理 RFCOMM 分片和粘包，拒绝非法类型、超长帧和
字段不完整的响应。

## 3. 握手

握手与 MOONDROP Robin 完全相同：

```text
发送：FF 01 00 00 00 0A 03 00
响应：FF 04 00 04 00 0A 83 00 00 04 03 01
```

名称只选择候选；只有完整握手响应合法时，私有协议才进入确认状态。精确型号握手失败时
Adapter 不改判为其他品牌，设备会话按统一恢复策略依次退避重试，达到边界后暂时休眠；
显式刷新或后续系统连接生命周期可以唤醒新的有界连接周期。部分自动连接捕获中耳机会在
连接后主动推送载荷相同的 hello 帧（`0A 83 00 00 04 03 01`），该帧同样满足握手校验。

## 4. 电量

查询命令：

```text
FF 04 00 02 00 1D 1A 01 01 02
```

查询响应与连接后的主动推送使用相同的数据布局：

```text
查询响应：FF 04 00 06 00 1D 1B 01 01 <LL> 02 <RR> 03 <CC>
主动推送：FF 04 00 06 00 1D 1A 81 01 <LL> 02 <RR> 03 <CC>
```

`LL`、`RR` 分别为左耳和右耳电量：`1..100` 为有效值，`00` 与 `FF` 均表示未连接
（如耳机在充电盒内）并解码为未知。`03` 键为充电盒电量，
`0..100` 为有效值（空盒 `0` 是真实状态），`FF` 表示不可读并解码为未知。没有充电状态
证据，因此实现不会构造充电状态。首次私有电量提交后，任一组件的明确不可读标记都会清除该组件旧读数；
初次仅收到未知值不确认私有电量能力。任一组件越界或缺失字段时整帧拒绝，
避免把垃圾字节解释为离线。

协议确认前电量沿用 Android 系统整机；收到合法电量帧后切换为私有左右耳与充电盒电量
并发布。连接初期的分侧回报会在会话内合并，并通过有界的单次查询补齐另一侧；补齐成功
或达到重试边界后才提交首次私有电量。此后完整快照直接替换，单侧入盒不会继续显示
旧电量。初期合并位于 Adapter 的逐事件规范化阶段，同一传输读取包含多帧时也按顺序
推进提交状态；协议会话只提供原始完整快照。查询会在握手确认后、显式刷新和电池特性重查时
发出，不创建常驻轮询。

## 5. 噪声模式

查询命令：

```text
FF 04 00 00 00 1D 40 03
```

查询响应中的首个参数表示当前状态：

| 参数值 | 状态 |
|---|---|
| `00` | 关闭 |
| `01` | 降噪 |
| `02` | 通透 |

设置命令：

| 状态 | 设置帧 |
|---|---|
| 关闭 | `FF 04 00 01 00 1D 40 04 00` |
| 降噪 | `FF 04 00 01 00 1D 40 04 01` |
| 通透 | `FF 04 00 01 00 1D 40 04 02` |

设置响应 `FF 04 00 01 00 1D 41 04 <模式>` 回显写入的模式（捕获中常见尾部 `40`，
同样不参与校验），可视为设备侧写后确认。设备设置后不保证主动回报模式，且刚写入后的
第一次查询可能仍返回切换前状态。现有证据不足以把缺失的单侧电量等同于佩戴状态，模式
控制不会据此建立额外门禁。Adapter 使用 `PUBLISH_AFTER_WRITE`：完整控制帧写入
成功后先发布用户选择的目标模式，并在 `onControlWritten()` 的事件作用域中声明 `600 ms`
后执行一次只读模式查询。若回报尚未达到目标，返回 `HOLD`，继续按 `500/700/900/1200 ms`
的有界序列声明下一次查询；回报目标模式后立即接受并取消后续请求，达到上限仍不一致则
接受最后真实回报，由统一状态链自然矫正卡片。后续确认只发送查询帧，不重复发送设置帧，
也不创建常驻轮询。

## 6. 代码边界

- `MoondropPuddingWireCodec`：纯字节帧、流式解码和字段校验（尾字节忽略）；
- `MoondropPuddingProtocolSession`：握手进度、遥测查询、控制编码和回读；
- `MoondropPuddingAdapter`：名称判型、传输候选、能力确认、模式写后确认及其有界状态；
- `StateRequestDispatcher`：按特性去重并执行 Adapter 声明的一次性查询，不包含型号策略；
- MiLink：只读取 Adapter 发布的标准电量与噪声状态，不增加自定义卡片或 View Hook。

## 7. 来源与证据

- 用户实测捕获的握手、噪声查询/设置与电量主动推送帧，含帧尾字节观察；
- 2026-08-15 实机验证：名称判型、严格握手确认与降噪/关闭/通透三态切换均正常。

与 MOONDROP Robin 公开协议同构的握手与帧格式见
[`moondrop-robin-protocol.md`](moondrop-robin-protocol.md)。HyperEars 根据可互操作的协议
事实独立实现，不分发厂商 App、固件、图片或上游程序。

## PuddingPods 3.47 application-length dialect

The executable reference is `lingbai-rong/PuddingPods` release v3.47,
`default.apk` SHA256 `EBE7C11AB40E988BB24AE765F736E1FDD4F9B929E8CBF4EFC64D57D83EEB4AE0`.
The published README declares GPL-3.0; the pinned repository contains no application source.
The adaptation reconstructs the executable wire contract, not its replacement Settings activity.
Detailed source locations and artifact provenance are in
[the APK audit](research/source-audits/oppo-pudding-findings.md).

This dialect counts the complete application payload. Handshake is
`FF 01 00 04 00 0A 03 00`; scalar writes use `FF 04 00 05` followed by
`00 1D sub opcode value`. Existing captured parameter-length packets remain unchanged.
A single session-owned delayed state request sends the APK handshake and read queries if
legacy negotiation has not selected a dialect. There is no permanent poll or new global timer.
The normal transport owner cancels pending requests on candidate failure, replacement and close.

Neither a name, endpoint, generic negotiation response nor a battery report selects this dialect.
A legacy six-parameter battery response has a valid APK one-pair prefix, so using battery as
selection evidence would be incorrect. Only an exact five-byte known noise/gain/LED application
report selects APK framing. The existing exact captured handshake selects legacy framing.
Conflicting positive evidence in one receive batch leaves negotiation unselected. Selection lasts
until protocol reset; later contradictory packets do not change the selected write grammar.

Only flags-zero frames are admitted as control evidence. APK `lc1` understands optional trailer
and extended-length flags but does not validate the trailer. No checksum algorithm is inferred.
Oversized lengths and unsupported flags cannot grant settings capabilities.

- Noise: query `00 1D 40 03`, write `00 1D 40 04 value`, report
  `00 1D 41 [03|04] value`. Values are off=0, adaptive ANC=1, transparency=2,
  wind=3, basic ANC=4. The typed state keeps adaptive and basic ANC distinct.
- Gain: query `00 1D 1E 01`, write `00 1D 1E 02 value`, report
  `00 1D 1F [01|02] value`. The APK enum proves low=2, medium=1, high=0;
  the adaptation document's reversed low/high mapping is not used.
- LED: query `00 1D 26 01`, write `00 1D 26 02 value`, report
  `00 1D 27 [01|02] value`, with exact boolean values 0/1.

Each field requires its own valid device report before writes become available. Noise readback is
scheduled 180 ms after a successful write; gain and LED use 140 ms. Submitted values do not become
confirmed state. The native noise card adds a session-owned details entry for five-mode selection,
gain and LED; a settings-only carrier is used when only gain/LED reports exist. Controls recheck
address, session token, private-channel state and module ownership at interaction time.

APK battery pairs are accepted only after dialect selection, require unique keys 1/2/3 and valid
0..100 percentages, and merge only reported components. This does not change captured legacy
zero/unavailable distinction or bounded bootstrap/stabilization, nor Robin/Mirage valid-frame behavior. Extended controls
on the legacy dialect remain unclaimed. Validation is source fixtures and automated tests;
no new hardware acceptance is asserted.
