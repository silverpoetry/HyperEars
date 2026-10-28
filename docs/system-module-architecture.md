# HyperEars 系统模块架构

本文描述当前实现的对象边界、会话生命周期和扩展约束。实现以“一台物理耳机、一个当前
Adapter、一个设备会话”为基本单位。

3.0.0 的原生展示边界见[原生耳机提示接入设计](native-headset-integration-design.md)。
Adapter 的静态 `artwork` 声明及默认图片见[展示资源规范](headset-artwork.md)。
新增后端消费现有已接受状态；连接代号、系统归属与生产者生命周期属于传输元信息，
不进入厂商协议状态。不同宿主与设备的实机验收范围单独记录，不按代码覆盖推断全部实测通过。

## 1. 目标与边界

HyperEars 在小米系统已有的蓝牙与 MiLink 流程上补充第三方耳机信息：

- 监听系统耳机连接生命周期；
- 为需要厂商私有协议的设备建立有限的 GATT、RFCOMM 或 L2CAP 通道；
- 把电量、噪声模式、物理形态和控制能力投影给 MiLink；
- 复用小米原生的 TWS/头戴耳机载体 ID，不建立自定义 MiLink 设备表；
- 只在具体呈现确有差异时进行一次性卡片扩展。

模块不替代 A2DP、HFP、系统音量、音频路由或 MiLink 的设备流转实现。

## 2. 模块划分

```text
protocol
  纯字节 WireCodec、帧解析器与协议常量

integration
  EarbudAdapter、ProtocolSession、能力/状态模型、Adapter Registry

system-module
  LSPosed 入口、系统蓝牙生命周期、私有传输、跨进程状态、MiLink 桥、运行看板
```

依赖方向固定为：

```text
system-module -> integration -> protocol
```

`protocol` 不认识 Adapter、Android、MiLink 或 Socket；`integration` 不持有 Android
蓝牙连接；`system-module` 不解释厂商帧。

## 3. 设备会话聚合

### 3.1 EarbudAdapter

`EarbudAdapter` 是一台物理耳机在当前系统会话内的聚合根。它统一拥有：

- 型号/家族身份与匹配分辨率；
- 物理形态和 MiLink 呈现 ID；
- 当前已确认的能力与支持的噪声模式；
- 统一的动态特性快照 `DeviceFeatureSnapshot`；
- 候选传输和控制确认策略；
- 一个 `ProtocolSession`。

Registry 存放工厂而不是 Adapter 单例。同一地址的新会话、不同地址的并行会话都会获得
独立 Adapter 和独立 ProtocolSession，不共享解码缓冲、序列号、ACK 队列或运行状态。
Registry 同时提供按品牌组织的只读 Adapter 目录。目录由同一组注册工厂生成，包含参与首次
匹配的型号/家族 Adapter，以及只能由协议身份升级得到的具体型号 Adapter；设置 UI 不读取
型号类或匹配条件。Bluetooth 进程解析设备时按稳定 Adapter ID 跳过已禁用项，并继续按原有
顺序寻找后续 Adapter，因此关闭具体型号后可以落到家族，关闭家族后可以落到标准 Adapter。
设置目录同时提供品牌总开关；它只批量更新该品牌下的稳定 Adapter ID，不引入第二套品牌判型。
会话管理器保留当前物理连接的原始 `EarbudIdentity`，设置变化时使用同一 Registry 重新解析；
只有 Adapter 结果发生变化才关闭旧会话并建立替代会话。家族握手阶段产生的协议确认替换也
继承同一禁用集合，不能重新启用已被用户关闭的具体型号。

Adapter 继承链只表达可复用行为：

```text
EarbudAdapter
  └─ StandardEarbudAdapter
       ├─ vivo / iQOO
       ├─ StarRing
       ├─ OPPO Enco
       ├─ Bose
       ├─ Edifier
       ├─ ROSESELSA
       ├─ NiceHCK
       ├─ MOONDROP
       └─ Sony
```

具体型号继承对应家族 Adapter，只覆盖已知差异。型号字节差异以内嵌的 WireConfig 表达，
不再存在与 Adapter 并列、可被 UI 或运行时单独查找的 Profile 抽象。

### 3.2 ProtocolSession

`ProtocolSession` 是 Adapter 内部可转移的协议状态核心，负责：

- 保存流式解码器、序列号、ACK/请求队列和握手进度；
- 把控制请求编码为完整写事务；
- 把输入字节转换为 `ProtocolEvent`；
- 提供协议事件触发的后续只读请求和控制回读。

它不选择型号、不创建 Adapter、不连接 Socket，也不向 UI/MiLink 发布状态。

当协议身份确认需要换成更具体的 Adapter 时，新 Adapter 可以复用原 ProtocolSession；若
线协议或传输不兼容，也可以选择新 ProtocolSession 并要求重启当前通道或重新连接。

### 3.3 WireCodec

WireCodec 只处理一类厂商帧的字节语义：封包、拆包、校验、字段解析。设备名称、能力、
传输优先级和 MiLink 呈现不进入 Codec。

因此三者关系为：

```text
EarbudAdapter
  ├─ 设备身份、能力、策略、运行状态
  ├─ WireConfig（型号字节差异）
  └─ ProtocolSession
       └─ WireCodec（纯字节转换）
```

### 3.4 DeviceFeatureState

`DeviceFeatureState` 表示一项可随耳机运行过程变化的、带类型的设备事实。电量、噪声模式和
后续的降噪深度、空间音频、EQ 等状态都以该模型表达，不再向 `EarbudState` 或
`AdapterRuntimeState` 增加全局字段。

```text
ProtocolSession
  -> ProtocolEvent.FeatureStateChanged
  -> EarbudAdapter.normalizeFeatureReport
  -> EarbudAdapter.onFeatureReported
  -> FeatureReportDecision
  -> EarbudAdapter.runtimeState.features
  -> EarbudState.features
  -> FeatureStateTransport
  -> MiLink / 应用 UI
```

`DeviceFeatureSnapshot` 按稳定 `featureId` 替换同类值，保证同一状态只保留最新值。
Adapter 通过 `featureStateContract` 声明可保留的状态类型；在家族探测细化、具体型号升级或
保守回退时，会话把旧快照转移给新 Adapter，并过滤新 Adapter 不理解的特性状态。

`FeatureStateChanged` 表示结构合法的设备观测，并不绕过 Adapter 聚合边界。默认 Adapter 返回
`FeatureReportDecision.ACCEPT`，观测立即进入统一状态；已验证存在连接初期暂态报告或控制写入后
延迟生效的具体型号，可以返回 `HOLD`，并通过事件局部的 `AdapterEventScope` 记录一次
`requestState(featureId, delayMs)`。获得可接受状态或达到型号自己的次数边界时，Adapter 返回
`ACCEPT`，并记录 `cancelStateRequest(featureId)`。

事件作用域只收集有序 `AdapterEffect`，不会在 Adapter 回调期间启动计时器、访问 Android 或
反向调用会话。Android 侧的 `StateRequestDispatcher` 仅保证同一设备、同一 `featureId` 最多
存在一个待执行请求，按声明时间串行调用 `ProtocolSession.query()` 生成查询字节；它不保存目标
状态、尝试次数和型号判断。通道断开、Adapter 替换、厂商 App 接管或设备会话结束时统一取消
所有请求。这样，Adapter 独立管理型号状态机，ProtocolSession 不持有协程、Socket 或 UI，运行时
也不会随着更多型号适配而堆积策略字段。

`CapabilitiesIdentified`、`ProductIdentified`、握手结果和 `DeviceLifecycle` 不属于动态特性
状态：前两者是协议证据，决定能否向用户开放能力；后两者描述会话生命周期。有效能力和状态
报告可以出现在同一帧中，但只有能力快照声明支持时，MiLink 才会显示相应控制。这个分离避免
把“设备当前值”“协议确认”和“连接状态”混成一组可互相矛盾的字段。

`EarbudState.battery` 与 `EarbudState.noiseMode` 只是由标准特性派生的只读平台视图。它们仅为
MiLink 的既有电量和 ANC 回调、以及通用诊断 UI 提供投影，不是核心状态存储。型号专属卡片
应读取自己声明的特性类型，不向公共状态类添加厂商字段。

新增特性状态时，在 `integration` 定义带稳定命名空间 `@SerialName` 的 `@Serializable`
`DeviceFeatureState` 子类型，在具体 Adapter 的 `featureStateContract` 中声明支持，并由
ProtocolSession 产出 `FeatureStateChanged`。跨进程 JSON、Intent extra 和状态替换均由框架处理，
CardAdapter 不需要也不得手写这些细节。

### 3.5 ControlRequest

控制操作使用强类型 `ControlRequest`。当前标准请求族包含 `Refresh` 和
`SetNoiseMode`；所有 Adapter 默认继承 `StandardControlRequestContract`，由当前 Adapter
的有效能力和已确认模式决定是否接受请求。普通标准耳机因此只保留刷新和 Android 原生
能力，不会因为卡片传入模式请求而创建私有控制通道。

跨进程链路如下：

```text
MiLink/CardAdapter
    -> ControlRequest
    -> ControlRequestTransport (versioned JSON envelope)
    -> Bluetooth process decode
    -> EarbudAdapter.supportsControl(request)
    -> ProtocolSession.encode(request)
    -> vendor bytes
```

`Refresh` 是公共的一次性状态同步请求。MiLink 耳机详情卡片进入可见状态，以及 HyperEars 主页
成为当前页面时，由各自的公共生命周期协调器发送一次 `StandardControlRequest.Refresh`；具体
CardAdapter 不负责刷新，也不会建立轮询。Bluetooth 设备会话将 1.5 秒内来自多个界面或 MiLink
进程的重复刷新合并为一次，并使用独立门禁，避免影响紧随其后的用户控制。具体
ProtocolSession 只查询已经验证、可读取的状态；标准蓝牙 Adapter 将其处理为安全空操作，继续
使用 Android 系统电量。

`ControlRequestTransport` 使用 Kotlin Serialization 自动生成请求层级的序列化代码，采用
稳定的 `@SerialName`、严格 schema 和 4 KiB 载荷上限。CardAdapter、Adapter 和
ProtocolSession 不手写 Intent extra、JSON、Bundle 或信封字段。未知版本、未知请求、未知
字段、畸形内容和超限载荷均拒绝；请求未通过 Adapter 契约时不会写入耳机。

Adapter 可为一个请求返回 `ControlExecutionPolicy`：`DEVICE_REPORT` 仅等待 ProtocolSession 的
权威回读，`PUBLISH_AFTER_WRITE` 在完整写事务成功后发布声明的特性状态且不增加回读，
`PUBLISH_AFTER_WRITE_THEN_REFRESH` 则先发布再执行回读。命令冷却同样属于该策略，且只在
Bluetooth 会话已持有活动通道时计入；MiLink UI 不维护型号专属的节流状态。

若具体型号已验证写入后的早期回读仍可能是旧状态，它可以在 `onControlWritten()` 中记录本次
控制目标，并通过 `AdapterEventScope` 声明第一次延迟查询。后续回报仍进入统一的
`onFeatureReported()`：达到目标时接受并取消待执行请求；尚未达到时暂缓并声明下一次只读查询；
达到有界次数仍不一致时接受最后真实回报，由统一快照矫正平台状态。该过程保持唯一
`executeControl()`，不会重复执行用户控制，也不会把型号重试逻辑放入 Android 会话和
ProtocolSession。

新增厂商或型号专属控制时，在 `integration` 中声明带厂商命名空间 `@SerialName` 的
`@Serializable` 请求子类型，家族 Adapter 通过 `controlRequestContract.extending { ... }`
增加已验证的请求和参数范围，具体型号继续收窄能力，ProtocolSession 仅增加该请求的
字节映射。卡片只构造请求对象并调用 `environment.controlSender(address, request)`。

例如，新增分级降噪请求只需要声明业务类型和 Adapter 契约：

```kotlin
@Serializable
sealed interface HonorControlRequest : ControlRequest {
    @Serializable
    @SerialName("honor.set_anc_depth")
    data class SetAncDepth(val depth: Int) : HonorControlRequest
}

open class HonorEarbudAdapter : StandardEarbudAdapter() {
    override val controlRequestContract =
        StandardControlRequestContract.extending { _, request ->
            request is HonorControlRequest.SetAncDepth && request.depth in 0..10
        }
}
```

框架在构建时自动生成该 sealed 请求层级的序列化与反序列化代码。CardAdapter 直接发送
`HonorControlRequest.SetAncDepth(depth)`；对应 ProtocolSession 通过类型分支读取 `depth`，
不注册命令字符串，不进行强制转换，也不维护传输层反序列化工具。

## 4. 首次匹配与协议细化

`EarbudAdapterRegistry` 仅在系统耳机连接时执行一次初始匹配，顺序为：

```text
具体型号 -> 厂商协议家族 -> 品牌标准回退 -> Standard Bluetooth
```

匹配依据来自 Android 已缓存的设备名称、Class、服务 UUID 和地址信息，不主动扫描。
Registry 不按 ID 恢复运行时 Adapter，也不参与协议升级。

Registry 结果只是 Bluetooth 进程中的静态候选。MiLink 执行原生 `checkIsMiTWS(device)` 后，
模块才在同一稳定入口仲裁最终所有权：原始结果表示支持时，系统所有权优先，并通过带发送方
身份的定向广播同步到 MiLink 的 `:audio`、`:core`、`:ui` 进程，同时关闭 Bluetooth 进程中
同地址的模块会话；原始结果表示不支持且存在活动候选时，HyperEars 才把结果补充为支持。
MiLink 子进程在收到系统认领前可以消费相同的活动候选快照，收到认领后，身份、电量、噪声
能力、命令、卡片扩展和设置跳转会统一退避，不单独维护品牌黑名单。

需要私有协议的 Adapter 在会话第一阶段执行只读确认。结果只有四种：

- `AwaitingEvidence`：初始请求已写入，等待有效响应；
- `Ready`：当前 Adapter 和 ProtocolSession 可继续使用；
- `Rejected`：响应明确不兼容，当前候选传输失败；
- `Replace`：返回新的 Adapter 及激活策略。

`Replace` 的激活策略为：

- `KEEP_CHANNEL_READY`：复用当前通道和 ProtocolSession；
- `RESTART_ON_CURRENT_CHANNEL`：保留通道，以新 Adapter 重新执行初始化；
- `RECONNECT`：按新 Adapter 的传输声明重新连接。

当前 Adapter 在返回 `Replace` 前完成 ProtocolSession、运行状态和已确认协议事实的转移；
`EarbudDeviceSession` 只在同一设备会话内原子安装结果。系统只保存一个 `adapter` 成员，
不存在初始 Adapter、effectiveAdapter 或 Adapter/Profile 双状态。

## 5. 能力真实性

初始匹配链中的家族与具体型号 Adapter 都只从标准流转、系统音量和 Android 整机电量
起步。名称、服务 UUID、设备形态和已知型号配置只授权选择候选协议，不直接授权私有
遥测、写能力或专用卡片：

1. 建立候选传输；
2. 发送只读握手/状态查询；
3. ProtocolSession 产生有效 `CapabilitiesIdentified`；
4. 当前 Adapter 更新已确认能力，或替换成协议确认 Adapter；
5. 发布新的完整 AdapterSnapshot；
6. MiLink 才看到对应控制项。

所有标准耳机从 Android 系统整机电量开始。`CapabilitiesIdentified(battery=true)` 只确认协议
具备电量遥测能力；只有经当前 Adapter 接受的私有电量状态才把来源晋升为私有协议。握手、
噪声能力响应或仍在型号初始化确认中的电量观测都不会提前停止系统电量更新。有效噪声状态/
协议能力响应才确认噪声控制。
失败或超时不会把
静态猜测能力留在卡片上。家族 Adapter 还可通过 `onInitialProtocolUnavailable()` 返回保守
替代 Adapter；声明的目标已被用户关闭时，统一门禁只允许直接回退到启用中的
`StandardEarbudAdapter`，标准回退也关闭时则保持休眠，不激活其他替代项。会话层只执行统一
决策，不包含厂商品牌判断。ROSESELSA 产品线候选使用该机制在首次私有协议始终未确认时退回
品牌标准能力。

Bose 是型号细化示例：BMAP 产品 ID 产生 `ProductIdentified(productId)`，Bose Adapter
把产品 ID 映射为具体 Adapter，并将已有 ProtocolSession 和运行状态转移过去。未知产品
只在只读 STATUS 明确确认 AudioModes、ANR 或 CNC 方言后开放相应控制。

## 6. 生命周期

`EarbudState` 只保存一个 `DeviceLifecycle`，不保存可互相矛盾的连接布尔组合：

```text
SystemProfileState
  DISCONNECTED / CONNECTED

PrivateTransportState
  NOT_REQUIRED / IDLE / CONNECTING / CONNECTED / RECOVERING / DORMANT

ProtocolHandshakeState
  NOT_REQUIRED / PENDING / CONFIRMED / REJECTED
```

`sessionActive`、`connected`、`privateChannelConnected` 和 `handshakeAccepted` 仅是从该对象
计算出的兼容视图。所有需要私有协议的初始 Adapter 都声明 `PROTOCOL_HANDSHAKE`，必须在
系统音频、私有传输和协议确认同时就绪后进入可操作状态。无需私有协议的标准回退保持
`NOT_REQUIRED`，不会建立厂商控制通道。

典型流程：

```text
A2DP/HFP connected
  -> Registry 创建初始 Adapter
  -> EarbudDeviceSession(CONNECTING)
  -> 候选传输 CONNECTED
  -> 需要确认时：协议 PENDING -> Ready 或 Replace -> CONFIRMED
  -> 无需确认时：协议 NOT_REQUIRED
  -> 广播完整状态快照
  -> MiLink 接收、查询身份/能力、刷新卡片
```

已确认协议的通道异常进入 `RECOVERING`，有界重试耗尽后进入 `DORMANT`。系统音频会话
仍保留，重新注册或显式刷新可唤醒连接。尚未确认的家族候选可按 Adapter 决策降级为不需要
私有通道的保守实现；A2DP/HFP 断开才销毁设备会话。

## 7. 传输与并发

`EarbudDeviceSession` 是私有传输唯一所有者。Adapter 只声明有序的
`EarbudTransportSpec`，包括：

- `RfcommEndpointSpec`；
- `GattTransportSpec`；
- `L2capEndpointSpec`。

`GattTransportSpec` 默认直接连接当前 A2DP/HFP 会话设备。对于音频端点与控制端点分离的
设备，Adapter 可以声明 `CompanionDevice`：运行层先检查已配对设备，必要时按指定的服务
UUID 或厂商数据执行一次有时限的 BLE 扫描；Adapter 提供只依赖平台无关观测值的关联规则。
厂商地址布局、名称规则和广播字段不会进入通用传输层，扫描结束、超时、会话取消或通道关闭
都会停止扫描并释放等待任务。

每台设备最多一个活动通道、一个 Reader、一个连接任务和一个串行写事务。控制写、协议
即时响应和回读都经过同一互斥写入路径，防止重复帧和交叉事务。全局协调器只串行化昂贵
的连接尝试，不限制多个已连接设备会话。

协议握手截止由传输层执行；计时器到期时关闭当前候选通道，使阻塞的 RFCOMM、L2CAP 或
GATT 读取退出，再进入既有的有限端点回退。成功握手会先取消截止任务，不会关闭活动通道。

### 7.1 厂商控制 App 退避

控制权仲裁与蓝牙连接状态是两个独立维度。HyperEars 在每个声明的厂商控制 App 进程中仅
Hook `Application.attach(Context)`，随后向 Bluetooth 进程登记一个进程级 Binder 令牌。
Bluetooth 进程使用 `linkToDeath` 监听令牌死亡，并按包名聚合多进程状态：只要该 App 的
任一已 Hook 进程存活，对应设备会话就进入 `EXTERNAL_APP`；所有进程死亡后才恢复
`MODULE`。整个过程不扫描蓝牙、不读取厂商 App 的连接对象，也不要求厂商 App 建立耳机
通道。

控制 App 目录只定义 Adapter 可使用的导航目标和私有通道所有权边界，不参与耳机判型。
当前应用名称、包名、Adapter 声明顺序和 LSPosed 配置见
[厂商控制 App 与 LSPosed 作用域](control-apps.md)。

进入 `EXTERNAL_APP` 时，`EarbudDeviceSession` 先设置控制权，再取消自身连接任务、关闭
当前私有通道、重置 `ProtocolSession`，并发布 `standardIntegrationProjection()`。该投影保留
MiLink 流转、系统音量、标准蓝牙电量和设备形态，移除私有传输、握手、噪声控制和型号专属
能力，避免卡片显示“可点击但实际无效”的控制项。控制 App 退出后，会话在仍连接的前提下
按同一有界重连策略恢复私有通道和协议确认；耳机已断开时不创建新的连接任务。

`HeadsetSettingsNavigator` 统一处理 MiLink 的“更多设置”、原生电量通知及连接弹窗内容点击。
各入口确认模块展示所有权后，传入真实地址、当前已接受的会话状态及点击时的用户设置。
导航器按策略打开真实蓝牙设备详情、HyperEars，或按 Adapter 声明的优先级选择已安装且有
Launcher Activity 的控制 App；当前持有控制权的 App 优先。后两种目标启动失败时回退到真实
蓝牙设备详情。导航不重建 Adapter，不参与设备判型或协议控制。
MiLink 入口解析优先使用稳定语义类和严格版本表；未知 MiLink 版本只有在两者均不可用时才执行一次
唯一结果的 DEX 语义指纹查询。查询结果按 APK 路径缓存，且不进入卡片绑定、点击或蓝牙会话
热路径；无法唯一确认时不安装入口 Hook。
LSPosed 没有公开的运行时作用域查询接口，
因此“已 Hook”以控制 App 实际发出的 Binder 登记为准；静态作用域只决定该登记 Hook 是否会
被安装。页面跳转本身不依赖该登记，运行时退避则必须依赖登记。

用户策略以 libxposed `RemotePreferences` 为跨进程单一事实来源。HyperEars 应用保留一份
本地镜像，只用于服务暂不可用时的界面读取、首次迁移和待同步写入；Bluetooth、MiLink 和
已 Hook 控制 App 进程直接绑定同一 RemotePreferences 组，并通过偏好变更监听器接收更新。
该链路不使用配置广播，也不轮询目标进程。

“暂停模块”通过同一 RemotePreferences 监听链路驱动每个进程的 `ModuleRuntimeGate`。
Bluetooth 进程暂停时
同步关闭全部 `EarbudDeviceSession` 并停止接收新的 A2DP 注册；MiLink 进程暂停时清空
`ProcessStateStore`、已知地址和卡片扩展状态，所有 Hook 直接返回原实现。暂停不会停用
Android 原生蓝牙服务，也不会修改 A2DP/HFP 或音频路由；恢复后由下一次系统连接事件创建
新的模块会话。

## 8. 跨进程状态与 MiLink

Bluetooth 进程发布的状态包含：

- 完整 `AdapterSnapshot`；
- `DeviceLifecycle`；
- 一个版本化 `DeviceFeatureSnapshot`；
- 地址、会话令牌和单调 revision。

`FeatureStateTransport` 把完整快照放入单一版本化状态信封；接收端拒绝未知 schema、未知
状态类型、畸形内容和超限载荷。状态 IPC 不再维护电量、充电、降噪等固定字段的散乱 extra。
MiLink 和应用 UI 直接消费完整快照，不按 `modelId` 重新访问 Registry。控制请求仍使用独立的
版本化信封，先严格反序列化，再由当前 Adapter 校验；状态快照不会被当作控制请求执行。

MiLink 设备 ID 只由 `AdapterSnapshot.formFactor` 映射：TWS 使用一个已知原生耳机载体，
头戴耳机使用一个已知原生头戴载体。具体型号不伪造新的设备 ID 查找表。

常规电量、噪声模式和能力通过官方查询路径提供。MiLink 仅认识原生电量和 ANC 三态，故桥层
在最后一步将对应标准特性投影到这些回调；该平台限制不会回流到核心状态模型。只有原生卡片
无法表达的、已验证的少量呈现差异，才由 `MiLinkCardAdapter` 在 View 创建/绑定时进行一次性
处理；该层只读取不可变状态快照，不连接蓝牙、不持有 Adapter 或 ProtocolSession、也不轮询。
卡片扩展只依赖稳定的原生 View ID，不 Hook 混淆回调：例如 Bose
AudioModes 两态设备保留系统三项布局，但把协议不支持的“关闭”项设为不可点击；支持
抗风噪的具体型号则由对应 Adapter 选择明确的卡片呈现 ID。共享的抗风噪 CardAdapter 同时
识别旧版 HyperOS 的原生 ANC 行和 HyperOS 4 的原生三态选择行，保留三个系统模式项，仅在
标题区增加降噪分支开关。

## 9. UI 投影

`DeviceSessionUiProjector` 只读取不可变状态快照，输出通用 `DeviceSessionUiModel`。
Compose 不导入具体 Adapter、ProtocolSession、WireCodec 或厂商类型。

已确认噪声能力同样在该投影边界转换为通用模式控件描述。主页点击只携带地址、会话令牌和
`StandardControlRequest.SetNoiseMode`；Bluetooth 进程仍由当前 Adapter 校验能力与模式集合，
所以 Compose 不会绕过协议门禁，也不需要知道具体型号。

应用内更新检查是与注入层隔离的 app-process 服务：自动检查只在 HyperEars 打开时触发并按
24 小时间隔限流，手动检查只由“关于”页触发。Bluetooth、MiLink 和厂商控制 App 进程不创建
更新检查器，也不执行网络请求。自动检查开关和上次检查时间保存在应用本地偏好中，不进入
libxposed `RemotePreferences`，因此修改该开关不会唤醒或重配任何注入会话。

每个会话展示三类真实状态：

- 耳机侧：系统音频、Adapter 分辨率、私有传输、协议确认；
- MiLink 侧：状态接收、身份查询、能力查询、运行时通知；
- 控制权：模块控制或专有控制 App 运行中；后者表示私有协议已退避，不表示蓝牙断开。

连接中、恢复中、休眠和协议拒绝均来自 `DeviceLifecycle`，不由 UI 根据时间或型号猜测。

## 10. 扩展规则

新增适配按以下顺序选择最小改动：

1. 仅名称/形态差异：增加具体 Adapter；
2. 同一线协议、字段不同：增加 Adapter 内 WireConfig；
3. 同一协议状态机、产品身份可确认：在 Adapter 握手阶段返回 `Replace`；
4. 新状态机：增加 ProtocolSession；
5. 新帧格式：增加 WireCodec；
6. 新动态状态：增加 `DeviceFeatureState` 子类型与 Adapter 状态契约；
7. 原生卡片无法表达的呈现：增加独立 MiLinkCardAdapter。

初始 Registry 在进程加载时验证统一门禁：任何直接注册且需要私有协议的 Adapter，都必须
以系统整机电量、流转能力、空噪声模式和空专用卡片开始，并使用 `PROTOCOL_HANDSHAKE`。
协议产品身份确认后创建的替换 Adapter 不属于初始匹配链，可按已确认产品配置原子发布能力。

不得让 Registry、UI 或 MiLink Hook 解析厂商帧；不得让 ProtocolSession 按零售名称选择
设备；不得通过共享 Adapter/ProtocolSession 单例复用会话状态；不得为型号专属状态向
`EarbudState`、`AdapterRuntimeState` 或 IPC 新增固定字段。

## 11. 性能约束

- 初始匹配只在系统连接事件发生时执行；
- 不做全局或周期扫描；只有声明独立控制端点的已连接会话可以执行一次带稳定过滤条件和
  超时上限的定向扫描；
- 每台活动私有协议设备一个阻塞 Reader；
- 状态仅在 Adapter 快照、运行态或生命周期变化时发布；
- 未确认或不需要私有协议的设备不建立额外通道；
- 卡片扩展只在对应 View 生命周期执行。

## 12. 验证重点

测试至少覆盖：

- Registry 每次返回独立 Adapter/ProtocolSession；
- 具体型号、家族和标准回退顺序；
- 家族能力在协议证据前保持关闭；
- Adapter 替换时 ProtocolSession 与运行状态正确转移；
- 特性快照在状态 IPC 往返中保持类型和替换语义，畸形或未知状态被拒绝；
- 生命周期枚举不会组合出虚假的 ready 状态；
- 跨进程 AdapterSnapshot、DeviceLifecycle 和特性快照往返一致；
- UI/MiLink 不依赖 Registry 按 ID 重建运行时对象；
- 一次控制只产生一次完整写事务。

### 报告的逐事件规范化

`normalizeFeatureReport()` 默认原样返回。模型需要将有实测依据的初期分侧报告合并时，
可在 Adapter 内覆盖；输入与输出都必须符合已声明的 feature contract，并保留同一 feature ID。
规范化与接纳按事件顺序交替执行，因此同一次传输读取中的后续报告能看到前一报告的提交结果。
Pudding 只在既有有界电量初始化阶段合并，提交后完整快照直接清除明确不可读分量；
协议会话不保存该模型的重试次数或提交策略。其他 Adapter 保持默认行为。
