# Sony Headphones 协议适配

## 范围

HyperEars 实现 Sony Headphones Connect 私有 RFCOMM 协议中 MiLink 所需的最小子集：

- Sony 私有 v1 服务 `96cc203e-5068-46ad-b32d-e316f5e069ba`；
- Sony 私有 v2 服务 `956c7b26-d49a-4ba8-b03f-b17d393cb6e2`；
- 初始化、序号和 ACK 请求队列；
- 单整机、双耳和充电盒电量；
- 降噪、关闭、环境声，以及已登记型号的抗风噪状态；
- 2026 代型号（WF-1000XM6）的 v2 环境声方言：子类型 `0x19`，通知帧负载为
  `69 19 <00> <开关> <降噪/环境声> <00> <等级> <00> <00>`，开关与模式字节位置与
  `0x15` 方言一致；写入帧按通知帧逐字节镜像（`68 19 00 <开关> <降噪/环境声> 00 14 00 00`）。

均衡器、触控分配、语音助手、固件升级和厂商素材不属于 MiLink 耳机流转所需能力，
当前不实现。

## 帧与生命周期

消息以 `0x3e` 开始、`0x3c` 结束。消息类型、序号、四字节大端负载长度、负载和累加
校验位于帧内；`0x3c`、`0x3d`、`0x3e` 使用 `0x3d` 转义。连接建立后只发送初始化
请求 `00 00`。初始化响应长度为 4 时使用 v1，为 8 时使用 v2。

部分 v1 固件（WH-1000XM4，已实机验证）会忽略第一帧初始化请求，只在重发后才返回
初始化响应。该精确型号在握手完成前收到设备命令时最多重发一次 `00 00`，与已捕获的
Sound Connect 行为一致；相邻型号和 Sony 家族候选保持零重试，通用握手时限不变。

Sony 通道一次只允许一个等待 ACK 的请求。HyperEars 的协议实例维护设备序号和请求
队列：收到设备命令后立即回 ACK，收到上一请求的 ACK 后才发送下一项。Socket、协程、
重连和 MiLink 发布仍由通用设备会话管理，协议对象不持有系统资源。

## 适配层级

1. `SonyEarbudAdapter`：只提供标准 A2DP/HFP、流转、音量和 Android 整机电量回退；
2. `SonyProtocolFamilyAdapter`：声明 Sony RFCOMM、握手、ACK 队列和 v1/v2 公共语义；
3. 具体型号 Adapter：内嵌 `SonyAdapterConfig`，声明外形、电池拓扑、环境声方言和
   服务优先级。

已登记 Adapter 覆盖 WH-1000XM2–XM6、WH-CH720N、ULT WEAR、WF-1000XM3–XM6、
WF-C500/C510/C700N/C710N、WF-SP800N、WI-SP600N、WI-C100、LinkBuds 和
LinkBuds S。未知 `WH/WI/MDR` 或 `WF/LinkBuds` 产品先进入协议家族；名称明确表示降噪
产品时开放通用三态，否则只读取协议电量。未知型号仍须返回合法初始化帧，私有通道
才会就绪；合法电量或环境声状态响应分别确认对应能力，名称本身不会提前开放控制。

## 证据与限制

协议帧依据公开协议文档、SonyHeadphonesClient 和 Gadgetbridge 的可互操作行为独立
实现。上述型号当前属于公开实现画像，尚未完成 HyperEars 本地逐型号实机验证；
WF-1000XM6 的 v2 握手、双耳与充电盒电量、`0x19` 环境声通知解析，以及 WH-1000XM4
的 v1 握手重发、整机电量、`0x02` 环境声方言均已依据真实设备流量验证（XM4 的握手
行为与 Sound Connect 抓包逐字节一致），模式切换写入待实机确认。型号名用于选择候选
Adapter 配置，Sony 私有 RFCOMM 初始化响应用于确认协议和家族能力；公共 iAP2
accessory UUID 不参与 Sony 品牌判型；`LE_` 广播影子名称不会创建第二个设备会话。

完整来源、固定提交和许可证见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。
