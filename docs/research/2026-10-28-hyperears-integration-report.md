# HyperEars 大而全版本：上游、型号、功能与原生界面整合报告

日期：2026-10-28。状态：调研报告，等待用户确认；尚未开始功能更新。

## 1. 目标与本轮范围

以 HyperEars 为基础，吸收参考项目中缺失或落后的型号、协议能力和系统集成逻辑，
形成可独立维护的大而全版本。所有已适配耳机使用统一的系统原生体验：蓝牙设备详情、
MiLink 卡片、连接弹窗与超级岛共享同一设备状态。需要时调整现有架构及作用域，
不以原项目的最小功能范围限制本次目标。

用户确认本报告后再实施。完成后先提交 PR；若上游明确拒绝，在用户 fork 继续维护。
原贡献、维护提交和实机证据分别保留，不把构建成功当成设备验收。

本轮检查了 21 个源码仓库、PuddingPods Release APK、nezha 上的 AndPods APK、
系统蓝牙及 Settings 动态插件、MiLink，以及当前开放和关闭未合并的 PR。
另刷新了 MOONDROP 官方产品 API。没有安装测试包、重启、切换耳机模式或改变 App 运行状态。

基线：HyperEars 本地提交 `26d3151`；nezha / `25128PNA1C`；
HyperOS `OS4.0.5.0.XPACNXM`，Android SDK 37。

## 2. 结论

1. 鸣谢表示曾经参考，不表示当前完整功能已吸收。部分上游 HEAD 未变，仍有大量旧有功能缺口。
2. 最重要的工作是补齐共同的高级功能状态与原生展示层，再按品牌接入协议；直接拼接各 App
   会产生重复连接、状态冲突、跳转独立界面和不同卡片风格。
3. AirPods 5 无线版具有可核对的识别资料。AndPods 与 LibrePods 都已有独立条目；
   HyperEars 的 Apple 路径目前被多处主动排除，最小 AAP 实现也只覆盖电量和三态噪声。
4. OPPO/OnePlus/realme、华为、Sony、Apple、ROSE、NiceHCK、QCY、Bose 都有显著功能差额。
   三星与 Technics 即使参考提交未更新，也有未吸收型号或功能。
5. 没有一个参考项目可以直接提供“所有品牌、所有功能、完整原生页面”。可复用的是真实
   原生组件接入点、协议事实、状态模型和测试夹具，最终统一界面仍需在 HyperEars 实现。
6. 不同来源存在真实冲突。Pudding 增益高低编码、GAIA 长度语义、OPPO 长帧长度公式、
   华为 Pro 4 功能报告等，需要明确分支和验证，不能按最新仓库名称直接覆盖。

## 3. 证据与型号清单怎么读

- **目录条目**：有名称或产品 ID；只证明候选身份。
- **实现证据**：存在可执行编解码、状态读取或控制路径。
- **上游实测**：其他项目或贡献者报告了具体设备结果。
- **本项目最终实测**：当前维护后的协议、生命周期和界面在真实设备复核通过。

本轮主要完成前两层及已有实测记录的核对，没有新增硬件验收。
“主线上已有家族候选”与“已有精确型号及完整功能”分别统计。

完整可枚举目录见[型号附录](2026-10-28-model-catalog-appendix.md)。
机器可读的[修订清单](inventories/upstream-revisions-2026-10-28.json)记录 21 个仓库的
完整 HEAD、日期和来源；具体型号数据位于同目录。计数不是支持设备总数，存在别名、
跨项目重复、实验配置与通用回退，不能相加后宣称全部实测。

## 4. Apple、Beats 与 AndPods

### 4.1 用户的 AirPods 5 无线版

AndPods 4.3.8（277），包名 `cn.xiaolongonly.andpodsop`，APK SHA-256：
`79484d3eb66ecfd2cf790f98e5c865c05e4607a6f52d4c86930a7947711a4bdc`。
其枚举包含 `AIRPODS5`（型号索引 54）和 `AIRPODS5_WIRELESS`（48）；
本机 AndPods 缓存也有“AirPods 5代（无线充）”条目。

LibrePods 当前具体型号表另列 AirPods 5 无线充电盒版本的
`A3439 / A3440 / A3441`；普通版列 `A3531 / A3532 / A3533`。
这些是来源中的型号声明，本轮尚未从用户耳机读取设备信息或捕获其完整广播，
因此实际耳机身份绑定、固件和能力仍需在实现阶段核对。

nezha 的系统蓝牙程序中，`airlibrary.utils.Config` 有独立的 L2CAP、基础噪声、
佩戴检测型号表；没有 AndPods 新型号索引对应的 `3020 / 3620` 字符串。
`AirLocalStorage.isFeatureSupport` 还接受云配置覆盖，不能只看静态表就认定全部原因。
本轮检查的预期云文件位置未发现覆盖文件；缓存只见 `1F20 / 2720`，未见新型号。
这是系统识别未跟上的具体线索，不等同于已完成连接故障的动态根因验证。

HyperEars 的阻断点包括 `PlatformReservedHeadsetPolicy` 的名称/UUID 保留规则、
Bluetooth 入口的保留检查，以及 Registry 未注册 Apple Adapter。
解除一处名称判断并不能完整启用 Apple 支持。

### 4.2 型号范围

AndPods APK 有完整 49 个枚举：31 个 Apple/Beats、6 个 HQB、8 个其他品牌、4 个未知或通用回退。
Apple 覆盖 AirPods 1/2/3/4/4 ANC/5/5 无线、Pro 1/2 Lightning/2 USB-C/3、
Max/Max USB-C/Max 2；Beats 的完整条目见附录。HQB 条目必须保留独立身份和协议证据，
不能因广播仿照 Apple 而继承全部 AACP 能力。

LibrePods Android 注册表有 11 个具体 AirPods 型号，与上述入耳式 Apple 集合对应，
没有具体 Beats 或 Max 注册项。HyperPods 默认分支仅按 AAP UUID 选择家族，
没有 AirPods 5 精确型号矩阵。

AndPods 远端展示目录大于 APK 的协议枚举。本轮提取了本地 49 条预置展示缓存，
匿名远端目录请求要求登录，尚未取得完整远端零售/皮肤目录。报告只把 APK 的 49 个枚举
称为完整编译目录，不把它等同于宣传的全部展示型号；展示缓存也不作为私有协议证明。

### 4.3 可吸收的能力

- 设备信息与固件、左右耳/盒电量和充电状态、佩戴检测。
- 独立的自适应噪声模式、模式循环、左右耳长按、音量滑动及按柄事件。
- 对话感知、个性化音量、单耳降噪、提示音、充电盒声音等按型号支持的设置。
- 头部追踪与姿态数据、连接设备及音频归属事件；相关型号的扩展传感能力单独登记。
- 重命名、辅助听力和 EQ 等高级命令，逐项核对模型声明、有效回报及实际调用路径。

HyperPods 的一些高级 setter 没有 UI 调用或完整读回，不能直接列为成熟功能。
其默认 HEAD 仍是 HyperEars 已引用的 `9796d947`；当前可见 `aap/main/os3-dev`，
未发现 HyperOriG 文档曾引用的现存 `os4-dev` 分支。
LibrePods 更新了型号表，但原来已有的 AACP 管理器也远超 HyperEars 的最小子集。

AndPods 的 EQ 包含手机侧 AudioEffect；“查找”包含最后连接位置、RSSI 接近度和媒体发声。
这些与耳机内置 EQ、离线查找网络、精确测距不是同一功能。整合清单分别登记，避免同名误合并。
头部追踪数据也不等同于完整空间音频渲染已经实现。

手机侧体验也纳入完整版本清单：通知栏/锁屏电量、桌面小组件、连接弹窗与显示时长、
主题和布局配置、电量语音播报、连接时音量预设、佩戴自动播放/暂停，以及把耳机按键映射为
播放/切歌/弹窗/电量播报/语音助手等快捷动作。安装包有对应 Activity、Widget、
`TTSSpeaker`、`VolumeUtil` 和音效实现；这些功能单独实现 Android 侧生命周期，
不伪装成耳机固件设置。商业账户、付费系统及素材商城不属于耳机能力整合目标。

## 5. 按品牌的新增和落后项目

### 5.1 OPPO / OnePlus / realme

1812z/OppoPods 当前目录 137 条：OPPO 56、OnePlus 32、realme 49。
Leaf 分支有 141 条，比前者增加 Enco X4、Enco Buds3s、OnePlus Buds Pro 4、
OnePlus Buds Clip。全部名称和产品 ID 见附录，均为候选目录证据。

HyperEars 精确配置只有 Air2 Pro、Free4、X3、Air5，加 OPPO/Enco 家族。
当前名字包含匹配还可能把后缀变体归到旧型号；OnePlus/realme 不应仅靠共享 UUID强认。

吸收重点：产品 ID 查询与型号细化、基于 `protocolIndex` 的噪声档位/通透映射、
EQ 与自定义 EQ、游戏/低时延、双设备、空间模式、佩戴、提示音、佩戴贴合测试、
Golden Sound 耳扫/听力个性化完整流程。
端点候选、长帧及分片重组要一起升级，不能只加按钮。

Star 文档和 Leaf 代码对多字节长度是否减一存在冲突；部分文档帧长度示例也不自洽。
先用真实帧和边界夹具确认，再启用长载荷功能。原生 Settings 的服务接入可参考，
固定轮询和直接按读块拆帧等做法需重写。

LE Audio 另设工作线：上游已有按地址启用、LE peer/group 关联、HFP 协商、GATT 保持、
配对完成及手动断连暂停等逻辑；跨手机 SIRK 所有权和整组流转仍有未实现边界。
连接成功与左右耳真实播放分别验收，不能只移植开关就宣称完整 LE Audio 支持。

### 5.2 华为 / 荣耀

HuaweiPods 有 18 条显式路由：FreeBuds 3、4、4E、5、5i、SE 4 ANC、6i、6、
Pro 3、Pro 4、Pro 5、7i、7；FreeClip、FreeClip 2、FreeArc、Eyewear、Eyewear 2。
Collector 与部分 Eyewear 别名复用路由，不重复计为独立协议。

OpenFreebuds 的 20 个市场型号集合为：HONOR Earbuds 2 / 2 SE / 2 Lite；
FreeBuds 3、4i、5i、6i、Pro、Pro 2、Pro 3、Pro 4、Pro 5、SE、SE 2、SE 4 ANC、
Studio、FreeClip、FreeClip 2、FreeLace Pro、FreeLace Pro 2。
两套目录需要合并去重，并保留头戴/颈挂/开放式的状态差异。

HyperEars 当前精确型号为 FreeBuds 5i、Pro 3、4、FreeClip 2；其余部分只有家族候选。
补齐方向包括型号专属 ANC/透传档位、EQ、手势、空间模式、佩戴、双连、低时延、
音质、提示音语言、智慧音量和头部动作等。FreeClip 2 从电量专用扩展到其真实已有能力，
不会因套用入耳式模板而显示虚假的降噪控制。

新增 FreeBuds 3 SPP 信息/电量/佩戴/手势路径可结合 HuaweiPods 的 HFP/ANC 路径。
Pro 4 在两个上游的能力声明和实测报告不同，要按设备固件核对。
当前 5i 档位的本项目失败记录必须在迁移时重新验证。

原生展示中，FreeBuds 7 已有实际调用 `MiuiHeadsetAncAdjustView.setPointCount(3)`
的参考，具有直接价值；自制拨盘和依赖兄弟视图隐藏的部分需要重新设计。

### 5.3 Sony

HyperEars 已有 WH-1000XM2/3/4/5/6、WH-CH720N、ULT WEAR；
WF-1000XM3/4/5/6、WF-SP800N、WF-C500/C510/C700N/C710N；
WI-SP600N、WI-C100、LinkBuds、LinkBuds S。
Gadgetbridge 的 19 个固定型号都在这个集合内；本项目另有 WF-1000XM6。
SonyHeadphonesClient 的旧实测范围另包括 MDR-XB950BT，需要保留旧协议候选。

SonyPods 的主要增量是 SPP/GATT、V1/V2、Table1/Table2 动态能力协商，
不能把“理论兼容 Sony”展开成全部型号已适配。
吸收环境声等级/人声聚焦、EQ/Clear Bass、DSEE、Speak-to-Chat、多设备与音源选择、
LE Audio、音质优先、手势/Quick Access、佩戴自动暂停和关机、语音通知/助手等。
旧 VPT 环绕、声场位置和 ANC optimizer 单独保留能力候选。
Safe Listening 声压数据也列为功能缺项，需要会话内有界采集和耗电验证。

Gadgetbridge 本次较 pin 的新增主要是初始化重试归零、线程/locale 处理；
许多高级协议能力此前已存在。其自有 App quick controls 不是 HyperOS 原生控件。

### 5.4 ROSESELSA / 弱水时砂

HyperRose 当前精确目录：EarFeel i5（EARFREE i5）、EarFeel i7、BudsFeel MK2、Ceramics U。
新增精确候选为 i7、Ceramics U；后者不能直接当成本项目 Ceramics Ultra 的别名。
保留 HyperEars 已有 Furina、琉璃 X、琉璃 Ultra 等已记录适配。

i7/MK2 的上游 profile 只声明四态噪声及游戏模式，并明确关闭高级控制。
Ceramics U 提供六态噪声、深度、通透等级、EQ、游戏、查找、增益和提示音/触控命令；
部分命令缺完整读回，需要单独验证。
i5 的深度、EQ、游戏和查找属于以前已有但本项目未接入的功能。

### 5.5 NiceHCK / 原道

明确产品仍为 OriG in。NiceHCK_Controller HEAD 未变，已有六种 ANC、七种 EQ、
游戏、低延迟、双设备、佩戴、独立风噪开关、codec 和固件查询。
HyperEars 目前折叠了部分噪声状态且主要提供电量/噪声。
独立风噪布尔状态与风噪 ANC 模式分开处理；EQ/codec 的固件差异需要有序版本比较和回报证据。

HyperOriG 的 OS4/Android 17 修订值得研究生命周期，但其文档明确缺实机显示验证；
阻止原生详情页并重定向独立 App 的设计不满足本次最终界面目标。

### 5.6 QCY

QCYpods HEAD 与现有 pin 相同，明确型号仍只有 Crossky C50S，加 QCY/Crossky 家族。
旧代码已有低延迟、睡眠、EQ v1/v2、自定义 PEQ、LDAC、双设备、动态 EQ、空间模式和
自适应音量；HyperEars 主要只接了电量/噪声。
可参考其 Settings/空间卡片映射，但需保留严格拆帧、未知值拒绝和写后读回。

### 5.7 vivo / iQOO

ScrewVivoTWS 明确型号只有 TWS 3e；可补双击映射、长按行为及噪声循环、DeepX、佩戴检测。
不能将 3e 的字段直接外推给 Air3 Pro 或所有家族型号。
保留 HyperEars 的 Air3 Pro 实测路径、3e 配置和既有 vivo/iQOO 家族目录。
本轮未从该来源确认新的具体零售型号。

### 5.8 MOONDROP / 水月雨

保留 Robin、Pudding、MIRAGE；结合开放 PR 新增 ZZZ-ANGELS-OWS。
PuddingPods 发布包 v3.47（`rongyi.puddingpods`，347）确认五种噪声、增益和 LED。
其 APK SHA-256 为 `ebe7c11ab40e988bb24ae765f736e1fdd4f9b929e8cbf4efc64d57d83eeb4ae0`。

必须先解决两个差异：APK 的增益编码为低=2、中=1、高=0，与文档相反；
APK 对 GAIA 长度的解释与 HyperEars 当前已捕获的路径不同。
把两者作为候选方言核对，不能直接改共享 Robin/Pudding 编码器造成回归。

官方 API 本轮有 108 条记录，其中 BT 52 条，包含别名和联名；完整 BT 记录见附录。
Dots、The Garden、EVO 2、EDGE 2、Space Travel 2/Ultra、Pill、Golden Ages 等是进一步
研究候选。芯片类型和 EQ 段数只帮助分组，不证明现有协议可直接使用。

### 5.9 Bose

bosectl 当前耳机/音频眼镜目录有 26 条，本项目缺少产品 ID `4083` 和
Frames 的 `402C / 404C / 4060 / 4061`。上游 `4083` 名称/代号仍含未确认信息，
Frames 中有只登记身份的条目；按候选纳入，避免把目录当完整协议。

已有型号可补 CNC 连续等级、三段 EQ、空间模式、模式配置/自定义档、按键映射、
侧音、多点、自动暂停/接听、语音提示和重命名。
bosectl 的不同型号支持状态不一致，按实际配置和读取响应开放。
noisecancel 是旧的窄功能来源，主要用来核对 QC35/NC700/QC45/QC Earbuds 模式语义，
不能把它的近似“关闭”映射覆盖真实不支持关闭的型号。

### 5.10 Samsung

GalaxyBudsClient HEAD 与 HyperEars 的家族扩展 pin 相同，但其 13 个型号中
初代 Galaxy Buds（R170）、Buds+（R175）尚无本项目精确 Adapter。
其他 11 款为 Live、Pro、Buds2、Buds2 Pro、FE、Core、Buds3、Buds3 Pro、Buds3 FE、
Buds4、Buds4 Pro，已在本项目配置范围内。

可研究完整的型号专属环境声/降噪等级、手势、游戏、EQ/自定义 EQ、佩戴贴合、
头部/空间状态、设备信息、查找与更多设置。初代有不同帧头，不能塞进新款解析分支。
固件更新、恢复和诊断工具另设明确工作流及验收，不混入自动初始化。

### 5.11 Technics

TechincsPods HEAD 与本地 pin 相同。除本项目已有 AZ60/AZ60M2/AZ70/AZ80/AZ100
产品线外，其匹配器还提到 AZ40、AZ30、EAH-A800；这只是名称候选，不代表已确认协议和形态。
已有可执行 ANC/通透 0–100 等级与自适应写入路径值得补充；当前上游部分状态是写前乐观更新。
游戏模式函数明确记录“协议未实现”，因此不是可直接移植的功能。
保留 HyperEars AZ80 已完成的三态回读结果，等级/自适应另做实测。

### 5.12 其余现有品牌

StarRing、荣耀 X5s Pro、Edifier 的现有实测适配纳入统一原生展示层，不因缺少新的上游
App 而遗漏。Edifier W820NB 双金标版和 Dreame/TheAstro FloS2 的新路径来自 PR，
详见 PR 审查附录。已有品牌的抗风噪、游戏和档位等扩展统一重做呈现。

## 6. 原生选项卡：已经查到的真实实现

### 6.1 MiLink 快捷卡片

当前 MiLink 的 `HeadsetSelectCardView` 持有选项列表与选中索引；
`HeadsetSelectItemView` 使用宿主自己的布局、选中背景、文字颜色和材料效果。
标准 ANC controller 创建通透/降噪/关闭三项，并通过 `HeadsetServiceController`
提交控制，再处理完成结果。

HyperEars 已复用这些原生三态组件；其额外功能仍有自行添加的布局、Switch 和
`SamsungTouchControlDetails` PopupWindow，用户观察到的风格差异有代码依据。
现有 `NativeAncModeRow` 对三项的严格假设也需要扩展为受控的能力驱动配置。

### 6.2 蓝牙设备详情页

真实实现位于 Settings 的动态插件，而不只在系统蓝牙 APK：

- `JavaActivity` 根据设备类型选择 `MiuiAirpodsFragment`、`MiuiHeadsetFragment`
  或第三方页面。
- AirPods 页使用 `airpodslayout`、原生电量组件、`AncController` 与
  `WearDetectionController`。
- Apple 设置通过 `DbHelper` 的 `airpodsRepository` 查询能力/状态、发送命令；
  `AirPodsHeadsetStateContext` 分发电量、佩戴和噪声更新。
- 小米耳机页面有 `MiuiHeadsetAnc`、`MiuiHeadsetAncAdjustView`、
  通透等级与 Preference 系列组件。档位视图有 `setPointCount`、
  `setCurrentPointIndex` 和等级变化监听器，具备真实复用入口。

现有 HyperEars 没有 `com.android.settings` 作用域。实现用户要求的完整系统详情页，
预计需要增加可选/明确展示的 Settings 接入，并处理插件类加载和页面生命周期。
这是本次版本的正式扩展方向，不受旧文档“不注入 Settings”的范围限制。

### 6.3 推荐的最终呈现

所有品牌共享设备头图/电量、噪声选项、档位、空间模式、佩戴、手势、EQ、连接管理、
查找与设备信息的页面组织；各型号只展示已经确认的项目。

宿主有等价组件时使用实际原生组件、主题、动画、无障碍和触觉反馈。
没有等价组件的复杂 EQ、听力校准、特殊拨盘等，扩展同一原生页面或其子页面，
采用统一宿主风格与类型化控制，不为每个品牌跳到一套独立设置 App。
报告区分“原生控件复用”和“原生风格扩展”，不把后者写成系统原有能力。

MiLink 快捷页展示常用项目，完整功能进入原生设备详情。应用自身保留设备管理、诊断和
设置入口，三处共用同一状态，不各自创建耳机控制会话。

## 7. 官方 App 作用域与退避

当前官方 App 作用域用于控制权共存，不是协议依赖。`ControlAppProcessHook`
只在 `Application.attach` 后登记一个 Binder 存活令牌；蓝牙进程的
`ControlAppPresenceRegistry` 校验包/UID、跟踪多进程，并通过 Binder death 回收存在状态。

`yieldToVendorControlApp` 默认关闭。打开且对应 App 已被 Hook 后，匹配的 App 进程
存活会使 HyperEars 让出私有协议通道；退出、崩溃或被强停后恢复。
这里判断的是进程存在，不是页面前台，也不是已证实 App 占用了蓝牙 Socket。
因此智慧生活等多用途 App 的后台进程也可能触发保守退避。

用户只使用 HyperEars 时不需要安装或勾选官方 App；仅“更多设置”跳转也不需要其作用域。
未勾选意味着缺少这条进程存在信号，不代表私有协议失效。
当前策略是 HyperEars 主动让步，没有通过 Hook 强迫官方 App 让步。

大而全版本保留可配置共存，并考虑按实际连接/设备使用情形改善过宽的进程级判据。
AndPods、LibrePods 等第三方控制器也属于共存对象；协议读取与控制权感知分别设计。

## 8. PR 审查结果

实际读取结果为 5 个开放、81 个关闭。关闭的 81 个中，59 个已合并、22 个未合并。
对 22 个未合并项逐一检查评论、维护关系和当前代码后，没有发现一个可以简单归类为
“整个功能被明确拒绝”的 PR；但存在局部实现被否定、原版被替换、作者自行关闭和未完成的功能。

### 8.1 五个开放 PR，三条功能线

- **#90 → #96：Dreame / TheAstro FloS2 / OpenPro S2。** 整机电量和四档音效。
  维护版保留作者提交，但最终提交 `eeca05ba` 仍缺作者实机复核，伴侣 App 身份也未确认。
  原生四项选择器可作为统一宿主输入；其先移除旧控件再插入新控件的流程缺少部分失败回滚，
  需在通用实现中修正，而不是直接推广到所有品牌。
- **#92 → #95：MOONDROP ZZZ-ANGELS-OWS。** 握手及左右耳电量，不开放 ANC。
  原 PR #92 的 2026-10-27 评论已经确认当前最终维护包 `b333f1fe` 实机通过；
  #95 正文“待复核”已落后于评论。包括重连、退避恢复、未知清理和双耳恢复的报告。
  耳机在单耳入盒且链路保持时可能继续发最后电量，官方 App 也如此；保留固件事实，
  不人为设置过期时间伪造离线。
- **#99：原生连接弹窗百分比缺失。** 共享展示层的启动时序问题，首次 Binding 应读取同一
  producer/session/connection 的最新已接受状态。不是型号特例，建议优先吸收。
  相同三个生产文件的旧包有实测，基于当前上游的最终 APK 尚未重新安装，这两层分别记录。

模拟合并显示 #95/#96 与当前基线只有 CHANGELOG 冲突，原作者提交祖先关系完整。
机械可合并、代码合理与最终实机通过仍是三个不同结论。详见[开放 PR 审查](pr-audits/open-prs.md)。

### 8.2 十个未合并关闭的设备 PR

九项已被维护版本接续：#62→#72、#61→#71、#55→#57、#54→#56/#59、
以及 #53→#58、#39→#45、#35→#43、#34→#42、#33→#41。
它们不是九份待重新移植的完整功能，需吸收的是仍遗漏的部分。

- **#50 W820NB 双金标版**：作者自行关闭，未写原因；无明确接续 PR。
  主线缺少其 BES V1 编码器及精确 Adapter。维护者否定的是按文字搜索按钮并重写选中/透明度
  的局部 UI 做法，并非明确拒绝该型号。纳入本次协议及原生呈现重做清单。
- **#34/#42 Pudding**：风噪、自适应/固件 3.5.5 回报与离线旧电量反馈仍有缺项。
  当前部分合并状态对空值保留旧组件，需要区分本帧未提供与明确离线。
- **#53/#58 FreeClip 2**：原生 Settings 多出错误 ANC 入口当时明确留在旧范围外，
  本次全原生目标应解决，而非保留旧范围限制。
- **#62/#72 FitBuds Turbo**：共享卡片维护后的实机缺口仍需补齐。
- **#54/#59 FitClip Ultra**：已有后续作者对最终原生模式卡的验证，不应继续写成全部待验证。
- **#35/#43 Pro 3**：保留既有设备/协议证据；可见维护说明不能替代最终维护版独立复核记录。

逐项关闭时间、评论和当前代码核对见[关闭设备 PR 审查](pr-audits/closed-device-prs.md)。

### 8.3 十二个依赖 PR

其中 #63/#64 被 #70 维护版承接；#7 被 #63 替代；#2 被 #15 替代；#9 改为其他更新方式。
其余 #29/#15/#8/#6/#4/#3/#1 的目标已由主线达到或超过。
机器人“不会再提醒”或“已无法更新”的评论不是技术拒绝意见，
这些 PR 没有形成另一批被遗漏的耳机功能。
详见[关闭依赖 PR 审查](pr-audits/closed-dependency-prs.md)。

## 9. 实施架构建议

1. **身份与能力**：统一候选目录，优先产品 ID/设备信息/合法握手，名称仅用于候选。
   按型号、固件、传输、特性分别记录证据，区分已配置、写入成功、设备确认与物理生效。
2. **协议与会话**：保留并扩展纯字节层、单设备会话和类型化状态/请求；
   按设备能力协商通道及方言，集中处理分片、ACK、重试、取消和重连。
3. **统一功能描述**：适配器声明标准及厂商特性、可选值、状态和操作；
   Settings、MiLink 与应用从同一描述生成界面，避免每个品牌手写一套卡片。
4. **系统原生宿主**：分别实现 Settings 插件、MiLink、弹窗/超级岛宿主，
   在经过验证的语义边界绑定当前地址；失配时回到标准展示，不污染其他耳机页面。
5. **控制权**：系统完全可用、系统部分支持、模块接管、外部控制器占用分别处理。
   MiLink 接纳某设备不等于所有功能完整。允许用户选择完整模块控制；同一私有通道
   始终只有一个当前所有者，界面可继续复用原生，不让两个后端竞争写入。
6. **资源与配置**：身份、图片、显示名称、协议能力相互独立；官方后续补齐支持时
   可退出模块接管，也允许用户在 fork 版本继续选择完整增强功能。

## 10. 实施顺序与验收

阶段顺序决定交付依赖，不缩减最终大而全范围：

1. 固定上游修订和缺项清单；把 PR 独有协议及来源冲突转为可复现夹具。
2. 建立统一原生功能描述和宿主层，用已验证耳机对照电量、三态、档位、游戏等代表功能。
3. 优先交付用户的 AirPods 5 无线版：真实身份、协议连接、电量、噪声/自适应、佩戴与
   原生界面闭环，再逐项加入该型号实际支持的高级功能。
4. 分品牌吸收 Apple/Beats、OPPO 三品牌、华为/荣耀、Sony、ROSE/NiceHCK/QCY，
   以及 Bose、Samsung、vivo、Technics、水月雨的缺项；现有所有型号迁移到统一呈现。
5. 完成复杂 EQ、连接管理、查找、听力/贴合与其他高级流程；保持各型号的支持范围和失败状态清晰。
6. 进行多设备、外部控制器、前后台、断连重连、未知值清理、主题/布局与耗电验证，
   产出可回滚安装包后提交 PR。未完成最终实机复核的贡献保留 Draft 和开放原 PR。

每一项都需区分解析测试、控制写入、设备回读、原生页面同步和真实耳机效果。
重启、安装、配对变更及实际模式/音量写入在实施阶段安排，本轮未执行。

## 11. 本轮分析边界

- AndPods 与系统包的 JADX 有部分方法反编译错误；关键识别和原生组件调用路径已读取，
  未宣称逐方法完整还原。Pudding 相关发送协程另有回退反汇编核对。
- AndPods 完整远端展示目录未取得；已完整覆盖本机 APK 编译型号枚举，展示资源目录不等同协议目录。
- 上游默认分支、已知相关分支与具体 Release 是本次快照；没有宣称审查所有历史实验分支。
- 未对每个高级功能执行硬件测试。部分源只有命令或目录，明确保留待验证状态。
- AOSP、libxposed、Miuix、DexKit、KernelSU 属基础依赖/界面参考，不作为耳机型号目录扩张来源。
  官方产品资料中的固件、图片和频响资源没有搬入本项目。

## 12. 交付物与确认范围

本报告、型号附录、修订清单、分源审查和 PR 审查共同组成评审材料。
本轮新增文件仅为研究文档和派生型号数据，`AGENTS.md` 与功能代码保持未修改。

请确认的是“大而全功能范围、统一原生展示、AirPods 5 无线版优先形成闭环、
随后按品牌吸收全部缺项、完成后先提 PR”的总体方案；确认后再进入具体更新。
设备实测、测试包安装和最终公开兼容性声明各自保留证据，不预先把候选写成已完成。

## 13. 分源证据索引

- [HyperPods：已引用版本及未吸收功能](source-audits/hyperpods-findings.md)。
- [HuaweiPods / SonyPods：具体路由、动态能力与原生组件](source-audits/huawei-sony-findings.md)。
- [OppoPods / PuddingPods：源码与 Release APK](source-audits/oppo-pudding-findings.md)。
- [LibrePods / HyperRose / NiceHCK / HyperOriG](source-audits/apple-rose-nicehck-followup.md)。
- [OpenFreebuds / SonyHeadphonesClient / Gadgetbridge / Sony 协议文档](source-audits/huawei-sony-reference-followup.md)。
- [Leaf OppoPods / QCYpods / ScrewVivoTWS / Star 协议资料](source-audits/oppo-qcy-vivo-reference-followup.md)。
- Bose：`aaronsb/bosectl@70570b2` 的 `python/pybmap/catalog.py`、`connection.py`；
  `danielgjackson/noisecancel@0391b61` 的协议说明与 Android 实现。
- Technics：`MartinKayJr/TechincsPods@a378106` 的 `pods/Packets.kt`、
  `RfcommController.kt`、`AppRfcommController.kt`、`utils/TechnicsDeviceMatcher.kt`。
- Samsung：`timschneeb/GalaxyBudsClient@754b6fd` 的 `Model/Constants.cs`、
  `Model/Specifications` 和消息编解码器；按当前本地 Samsung 实现核对缺项。
- 原生 ROM 与 AndPods 的提取、反编译材料保留于 `.analysis/andpods-4.3.8/`，
  Pudding 发布包于 `.analysis/upstreams/PuddingPods/artifacts/`；这些二进制及反编译文件不入库。

许可和来源状态随具体修订记录。源码吸收保留相应归属与许可证；闭源 App 作为行为和
互操作事实参考，独立实现功能。Gadgetbridge 的 AGPL、协议文档 CC BY-SA/CC0 与
MIT/GPL 源码分开处理，不将整个参考集合视为同一种可直接复制的代码。
