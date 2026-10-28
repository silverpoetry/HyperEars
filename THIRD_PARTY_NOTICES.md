# Third-party notices and research sources

HyperEars 的原创代码整体按 GNU GPL-3.0-only 发布。以下项目为协议研究、行为对照或
架构参考来源；HyperEars 不随 APK 分发它们的应用、反编译产物或品牌资源。

## OPPOPods

长帧与分片还核对 [QuickBuds 固定提交](https://github.com/spizganed/QuickBuds/tree/6e069325984bab50c5bd331ced8805923c1db410)
（GPL v3 LICENSE）的 `docs/PROTOCOL.md`、`protocol/Wire.kt`、协议测试与
`testdata/protocol_cases.json`。其标为 CAPTURE 的长度头证明直接 LEB128 编码，
消除旧资料减一规则的歧义。本项目独立实现有界解码，回归样例仅保留长度头并使用合成
载荷，不分发源抓包中的听感或设备数据。长 EQ 写入还要求本会话的长帧及帧预算证据。

- 1812z/OppoPods: <https://github.com/1812z/OppoPods>
- Leaf-lsgtky/OppoPods: <https://github.com/Leaf-lsgtky/OppoPods>

用于理解 OPPO 私有 RFCOMM 帧、电量、降噪和 HyperOS 系统集成路径。1812z 项目
README 声明 GPL-3.0。本项目许可证及其与外部资料的关系以各自仓库中的许可证原文为准。

身份目录与产品 ID 适配参考 Leaf 固定提交 `929d813f1b82d5b09dd39a84b4f18744e8ef7e64`：
`pods/Packets.kt` 的 `ProductIdParser`、`pods/DeviceModelRegistry.kt` 及
`app/src/main/assets/device_models.json`。Leaf README 声明 GPL-3.0，该提交无独立 LICENSE。
`OppoProductCatalog.kt` 整理其 141 条产品 ID、名称及子销售别名，仅保留 SPP 耳机候选资格，
不导入功能标志、厂商图片或原始云配置。协议实现独立接入 HyperEars 会话与能力确认模型。
目录不是逐型号实机验证清单；118 条明确 SPP 耳机可探测，其他 23 条仅作目录记录。

同一 Leaf 提交的 `Packets.kt`、`CapabilityProfileFactory.kt`、`DeviceCapabilities.kt`
和 `EqualizerPage.kt` 用于核对日常开关、EQ 列表与自定义条目更新。74 份内置 EQ ID 集
仅用于排除禁止编辑的内置槽位，不据此开放功能。控制仍需实际状态、条目与产品 ID 证据。

## HyperRose / ROSESELSA protocol profiles

新增 EarFeel i7 与 Ceramics U 身份核对提交 `4e212f79bb1929892e4ac8ae22ed5c2d351e6944`
的 `EarFeelI7Profile.kt`、`CeramicsUProfile.kt`：分别保留 RFCOMM 端点和型号边界，
i7 仅接受其公开画像的四态噪声回报。Ceramics U 独立于 Ultra；新增型号尚未实机复核。

- DOHEX/HyperRose: <https://github.com/DOHEX/HyperRose>
- Reviewed revision: `635c0dd89ee07aab95c4138ca1275e44fd666165`
- Revision link:
  <https://github.com/DOHEX/HyperRose/tree/635c0dd89ee07aab95c4138ca1275e44fd666165>
- Declared license: GNU GPL-3.0（README 声明；该固定提交未包含其链接指向的
  `LICENSE` 文件）

用于核对 ROSESELSA EARFREE i5 的 BLE GATT 特征、电量和四态噪声控制帧，以及 ROSE
BudsFeel MK2 的 RFCOMM、校验帧和 LTV 状态字段。HyperEars 在自身 Protocol/Adapter
边界内重新组织实现，不分发 HyperRose 的应用、界面、图片或其他品牌资源。上游提交
缺少许可证文件的问题在此如实记录；HyperEars 不将该仓库的代码或资源作为可再分发
组件纳入 APK。

## HyperPods and LibrePods / Apple AAP

- Art-Chen/HyperPods:
  <https://github.com/Art-Chen/HyperPods/tree/9796d947daa18a379948349442632510424a1a15>
- Upstream license: GNU GPL-3.0
- Copyright: Copyright (C) 2024 Art_Chen
- kavishdevar/aln（现 LibrePods）:
  <https://github.com/kavishdevar/aln/tree/b5a3eaee8fbe5a0c83c360bb0fdcd6705a59cc25>
- Upstream license: GNU GPL version 3 or later
- Copyright: Copyright (C) 2025 LibrePods contributors

用于交叉核对 Apple Accessory Protocol 的 SDP UUID、BR/EDR L2CAP PSM、初始化帧、
组件电量通知和降噪状态。HyperEars 在现有 AAP 子集上逐步增加类型化控制，不使用或分发
上述项目的名称、图标、图片、界面和其他品牌资产。

Apple 四态聆听、设备信息与高级设置扩展还核对了以下互操作资料：

- AndPods 4.3.8（`cn.xiaolongonly.andpodsop`）：用户已购买的本地应用，仅提取聆听值、
  控制帧、设备信息字段结构和行为事实，独立实现；不复制或分发应用代码、反编译产物、身份密钥或素材。
- NEORUAA/HyperPods-X，固定提交 `018765be94f24a49bf28be26fbb5924a3652018f`：
  <https://github.com/NEORUAA/HyperPods-X/tree/018765be94f24a49bf28be26fbb5924a3652018f>。
  上游代码声明 GPL-3.0-or-later，基于 YeFeng233/HyperPods-X，并注明 LibrePods 等来源。
  本轮用于交叉核对四态值、控制回报、十四项设置的值域与多字节保留规则，以及 A3441
  实测边界。后续还核对佩戴／对话活动值、长按与 EQ 字段，以及有界媒体行为与
  VolumeShaper 框架接口；相应生命周期和媒体控制独立实现。没有移入其应用连接管理器、
  Glasmio 界面、SF 字体或 Apple 图片。

`AppleMotionCodec.kt` 的有界 protobuf 读取、四元数与校准代码由上述 HyperPods-X 固定
提交中的 `Motion.kt`／`IoSerial.kt` 适配，保留 GPL-3.0-or-later 声明，并在本项目组合发布时
采用 GNU GPL 第 3 版。原代码注明 RTBuddy 布局研究来自 FIocker/aap-head-tracker 与
Untrosss/HeadTracker；此处保留其来源说明。设备运动数据的接收、跨进程宿主边界和资源
生命周期另按 HyperEars 现有会话结构接入，不引入上游应用界面或字体素材。

设备信息的长度与字段顺序以 AndPods 4.3.8 的实际 DEX 行为交叉确认；自适应型号候选
另参考 LibrePods 固定提交 `89356c08cea3db0b6b54792d9329d09a87cc85fa` 的
`android/app/src/main/java/me/kavishdevar/librepods/data/AirPods.kt`。
来源：<https://github.com/kavishdevar/LibrePods/tree/89356c08cea3db0b6b54792d9329d09a87cc85fa>，
GPL-3.0-or-later，Copyright (C) 2025 LibrePods contributors。
仅使用协议与型号能力事实，不引入其图片资源。型号候选仍须结合本会话合法聆听回报；
来源声明不等同于本项目的各型号实机验证。

## NiceHCK Controller

- ZaeXT/NiceHCK_Controller:
  <https://github.com/ZaeXT/NiceHCK_Controller/tree/f1348a8d09fc57e3b7098a4f15bae5f926475771>
- Upstream license: MIT

用于核对 NiceHCK/YuanDao OriG in 的 RFCOMM UUID、帧长度、操作码、电量字段和噪声
模式枚举，以及游戏、低延迟、双设备、入耳检测、独立风噪抑制、EQ 和固件回报。
实际 EQ setter 使用无偏移枚举，最后两项受 minor 字段限制；不复刻源控制器游戏分支
落入低延迟分支的错误。保留其上游 MIT 声明如下：

```text
MIT License

Copyright (c) 2025 Zhang Xinlin

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## HyperOriG behavioral reference

- KiriChen-Wind/HyperOriG:
  <https://github.com/KiriChen-Wind/HyperOriG/tree/d2f2a4b1111e085f9720cc32ed072abb343778d0>
- License status at reviewed revision: no license file or explicit software license found

该项目只作为 YuanDao OriG in 用户可见行为的交叉参考。由于上游没有明确授予程序代码
许可，HyperEars 不复制其程序代码、界面或资源；协议实现依据 MIT 许可的
NiceHCK_Controller 及可互操作协议事实独立完成。

## Community research index

- 酷安帖子“HyperRose - 为你的弱水时砂耳机接入澎湃超级岛”：
  <https://www.coolapk.com/feed/71349652>

该帖子用于发现相关开源项目。实际协议采用、许可证判断和归属均以上述固定提交与其
仓库文件为准，帖子本身不作为程序代码来源。

## Pods Protocol Reverse Engineering

- Star-ZER0/Pods-Protocol-Reverse-Engineering:
  <https://github.com/Star-ZER0/Pods-Protocol-Reverse-Engineering>
- Upstream license: CC BY-SA 4.0

用于交叉验证 vivo、OPPO 等厂商协议字段，并提供 MOONDROP Robin / 水月雨知更鸟的
SPP 握手、电量查询与三态噪声控制协议事实。Robin 参考固定提交：
<https://github.com/Star-ZER0/Pods-Protocol-Reverse-Engineering/blob/2d97d85b2cde9ee1446e9e7f67c222ac9b9f2bb9/handmade/MOONDROP-Protocol.txt>。
相关文档在引用处保留来源链接；HyperEars 根据互操作协议事实独立实现 WireCodec 与
ProtocolSession，不复制上游程序、厂商资源或应用代码。上游文档采用 CC BY-SA 4.0。

## MOONDROP 产品目录快照与客户端协议蒸馏

- MOONDROP 公开产品 API 快照：
  <https://cdn-service.moondroplab.tech/api/v1/products/all>（只读 GET，抓取日期
  2026-09-16）
- MOONDROP Android 客户端二进制的 `classes.dex` 全量反汇编（baksmali 2.5.2），仅作
  互操作研究，反编译产物不入库。

用于协议文档中 MIRAGE / Pudding / Robin 的型号事实（UUID、
`chipType`、EQ 段数、OTA 与形态标记），以及 MIRAGE 候选适配的平面推断与协议边界
登记。本仓库只记录可互操作事实与 `文件:行` 引用，不分发 APK、原始响应、频响曲线、
壁纸或其他厂商版权素材；快照更新仅通过人工再蒸馏提交完成，运行时零外联。

## ScrewVivoTWS

- moculll/ScrewVivoTWS: <https://github.com/moculll/ScrewVivoTWS>

用于 vivo TWS 3e 的公开协议画像。该仓库当前未被 GitHub 识别出标准许可证；本项目
只记录可互操作的协议事实和独立实现，不复制其程序或资源。

高级设置协议核对固定提交 `81beeef0aaca22c7018ba03863c4f0ef759fde9f`：RFCOMM JSON
状态、左右双击、长按循环、DeepX 和佩戴检测。仅在 TWS 3e 的实际报告确认后开放，
不把这些命令外推到其他 vivo / iQOO 型号；通话手势缺少明确回读，尚未开放。

## Bose BMAP research

- aaronsb/bosectl: <https://github.com/aaronsb/bosectl>
- danielgjackson/noisecancel: <https://github.com/danielgjackson/noisecancel>
- Upstream licenses: MIT

本轮日常控制固定核对 bosectl `70570b29c1d3c9ba775b08a29eaea72385de27b5`
（Copyright 2026 Aaron Bockelie）及 noisecancel
`0391b614fffdf20ca5f3cdd42413dbae194e0740`（Copyright 2020 Daniel Jackson）：
三段 EQ、提示音位、双设备、限定型号自动暂停、CNC 强度与 QC35 ANR 低档。
每项按产品 ID 与合法 STATUS 回报开放，不根据同族名称外推可写功能。

用于交叉核对 Bose BMAP 产品 ID、RFCOMM 通道，以及 QC35/35 II、NC700、QC45、
QuietComfort Earbuds 和 QuietComfort Ultra 二代的控制帧。HyperEars 只独立实现互操作
所需的协议事实，不随 APK 分发上述项目的源代码、抓包、二进制或品牌资源。

## Edifier BES protocol research

Edifier W860NB PRO 的 BES RFCOMM 帧、电量和 ANC 槽位来自本项目对 Edifier Connect
v8.4.39 的互操作行为分析，并由真实设备通信验证。花再 Evo Pro 的 `0x1B` ANC 槽位、
`0xF2` 聚合电量帧和六值模式映射由社区贡献者 MYHealer 在
[PR #17](https://github.com/silverpoetry/HyperEars/pull/17) 中提供实机抓包证据；当前实现依据
这些互操作协议事实按现有 Adapter/ProtocolSession 架构独立编写。HyperEars 不分发
厂商应用、反编译产物、图片或品牌资源；其他 Edifier 型号只在合法 BES 响应确认相应
能力后开放。

FitClip Ultra 的游戏模式、盒电量与单耳零值报告由 aodianjun 在
[PR #54](https://github.com/silverpoetry/HyperEars/pull/54)、
[PR #59](https://github.com/silverpoetry/HyperEars/pull/59) 和
[PR #61](https://github.com/silverpoetry/HyperEars/pull/61) 中提供；FitBuds Turbo 的明文 BES
负载、`0x1B` 模式及游戏控制由 sksnnjj 在
[PR #62](https://github.com/silverpoetry/HyperEars/pull/62) 中提供（原提交
`6bd1312947c4f831197ba5129fbe9368adaeebf3`）。原作者提交保留在维护分支历史中，后续按本项目
GPL-3.0-only 许可整理状态生命周期、能力门禁与共享卡片实现。

## Sony Headphones protocol research

- Plutoberth/SonyHeadphonesClient:
  <https://github.com/Plutoberth/SonyHeadphonesClient/tree/5620e8ed5deccb957338b54e371b215146080819>
- Upstream license: MIT
- Gadgetbridge Sony Headphones implementation:
  <https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/15d45691c7eed5195fb7020dfefda49ff2be8a68/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sony/headphones>
- Upstream license: GNU AGPL-3.0-or-later
- Sony protocol documentation:
  <https://ohm-app.github.io/sony-headphones-bluetooth-documentation/Protocol/Specification/>

用于交叉核对 Sony RFCOMM 服务、帧转义、ACK 序号、v1/v2 初始化、电量类型及环境声
控制语义。HyperEars 根据可互操作协议事实独立实现 MiLink 所需的最小子集，不复制
Gadgetbridge 的 AGPL 程序代码，也不分发 SonyHeadphonesClient、厂商应用、图片或品牌
资源。SonyHeadphonesClient 的 MIT 许可文本由其固定提交中的 `LICENSE` 提供。

## QCYpods protocol research

- SouthautumnYa/QCYpods:
  <https://github.com/SouthautumnYa/QCYpods/tree/7bfbd951ad3fb803be84864a2a8493c78695bd61>
- Reviewed revision: `7bfbd951ad3fb803be84864a2a8493c78695bd61`
- Declared license: GNU GPL-3.0（仓库 README 声明；该固定提交未包含独立的许可证文件）

用于核对 QCY `A001/1001/1002` GATT 端点、`0xFF` 命令流、组件电量 `0x2F`、噪声模式
`0x0C`、厂商数据 `0x521C` 及伴生控制端点关联方式；同时参考低延迟、睡眠、LDAC、
双设备、动态 EQ、厂商空间模式、自适应音量、EQ 预设与十段 PEQ。HyperEars 在自身
Adapter、ProtocolSession 和 WireCodec 边界内重新组织实现，按实际回报逐项开放控制；
不分发 QCYpods 的应用、界面、图片、品牌资源或构建产物。上游许可证状态按固定提交如实
记录，具体条款以其仓库声明为准。

## TechincsPods / Technics RACE research

- MartinKayJr/TechincsPods:
  <https://github.com/MartinKayJr/TechincsPods/tree/a378106659d4a90ed7ac28a4ac94d592bebbec85>
- Reviewed revision: `a378106659d4a90ed7ac28a4ac94d592bebbec85`
- Declared license: GPL-3.0（仓库 README 声明；该固定提交未包含独立的许可证文件）

用于核对 Technics EAH-AZ TWS 的 Airoha RACE 帧、RFCOMM UUID、组件电量查询和噪声模式
写入序列。协议事实以固定提交的当前代码为准，不采用其 README 中英文功能说明或旧协议笔记
作为当前行为证据。HyperEars 只在自身 WireCodec、ProtocolSession 与 Adapter 边界内实现
MiLink 所需子集，不分发 TechincsPods 的应用、界面、图片、APK、抓包或厂商品牌资源。
上游许可证状态按固定提交如实记录，具体条款以其仓库声明为准。

## OpenFreebuds (Huawei protocol research)

本轮日常控制另核对 OpenFreebuds 固定提交 `0b4f14c0b500d491f0fb4ea38f2984ec334e3649`
及 [HuaweiPods](https://github.com/Nshpiter/HuaweiPods/tree/dc5b9d14e9030be95b178699d674d1a3217e1e6d)
（GPL-3.0 LICENSE）。来源包括各型号处理器的佩戴、双设备、EQ 和手势读写合同；
FreeClip 2 的 EQ 与手势取其明确捕获映射。按现有架构独立实现，未复制应用界面或资源。

## SonyPods daily controls

同一固定提交的 Table1 AUDIO/SYSTEM 编码与测试还用于核对 DSEE、连接质量和
Speak-to-Chat；V1/V2 各自的开关值、可用状态及 inquired type 独立保留，未从
功能枚举推导尚无写入实现的 V1 音质控制。

- [Mercury000/SonyPods](https://github.com/Mercury000/SonyPods/tree/3f701ff8f9b30c6909b9dbe8d99248f20d55560e)
- License: GNU GPL v3（固定提交包含 LICENSE）。

用于核对 Table1 功能发现、V1/V2 EQ、ClearBass、环境声，以及外层 Table2 通道隔离。
实现重新组织在 HyperEars 的 codec、会话与类型化控制边界内，不复制上游界面、品牌图片
或 Gadgetbridge 的 AGPL 代码。实际功能按设备发现和状态回报开放，未逐型号实机验证。

## OpenFreebuds baseline references

- melianmiko/OpenFreebuds: <https://github.com/melianmiko/OpenFreebuds>
- Reviewed revision: `035bf2a87cdda9e9c8e8e90c662b7fa61270c6ee`
- Upstream license: GNU GPL-3.0（仓库 `LICENSE` 文件，版权归 MelianMiko）

用于理解华为 FreeBuds Pro 3 的私有 SPP 通道 1 帧格式与 CRC16-XMODEM 校验、电量查询
（读响应 `[level, mode]` 与写命令 `[mode, level]` 的字节序差异、组件电量与整机充电标志
语义）、ANC 档位与透传档位枚举、`2B 03` 降噪变更通知等协议事实；共享帧格式和校验规则也
用于交叉核对 FreeBuds 5i 的独立捕获记录。HyperEars 在自身
WireCodec、ProtocolSession 与 Adapter 边界内重新组织实现，不复制或分发 OpenFreebuds 的
程序代码、应用、界面、图片或品牌资源；上游 GPL-3.0 义务以该仓库 `LICENSE` 原文为准。

## Huawei FreeBuds 5i capture evidence (hardware-verified)

FreeBuds 5i 的型号专属证据来自用户提供的 Android `btsnoop_hci` 文件和同步人工时间线，并已由
HyperEars 端到端实机验证复核。Channel 16、短模式帧和模式值的语义按“抓包确认”或“强推断”分别
标注，不能证明其他固件或其他型号通用。该材料不包含可再分发的软件代码、厂商资源或独立许可证要求。

## Huawei FreeBuds Pro 3 / 智慧音频

华为 FreeBuds Pro 3 的协议字段语义依据 OpenFreebuds 的公开实现、贡献者实机记录与
可互操作协议事实；华为智慧音频（`com.huawei.smartaudio`）仅作为控制权边界与导航目标登记在
`ControlAppCatalog`、`scope.list` 与[控制 App 目录](docs/control-apps.md)中，不作为耳机
品牌或型号的识别证据。HyperEars 不随 APK 分发华为应用、反编译产物、固件、抓包文件、
图片或品牌资源；所有商标、产品名称归其各自权利人所有。

## GalaxyBudsClient / Samsung Galaxy Buds protocol research

- timschneeb/GalaxyBudsClient: <https://github.com/timschneeb/GalaxyBudsClient>
- Reviewed release: `5.1.2`（用户提供的源码归档，修订标识 `b39e90c`）
- Family expansion review: `754b6fdfb1c151b022cece775c8aaad4eb185afa`（2026-10-03）
- Upstream license: GNU GPL-3.0

用于核对 Galaxy Buds2 Pro 的 RFCOMM UUID、`FD/ DD` 帧、CRC16-XMODEM、扩展状态字段、
三态降噪与设置命令。HyperEars 在自身 WireCodec、ProtocolSession 和 Adapter 边界中重新组织
实现；Fix25 同时核对 Live / Pro 的传统 SPP、布尔 ANC 与触控锁，以及新款共同长按字段。
Core / Buds3 FE 的参考布局仍有 TODO，不把家族外推记为实测，不分发参考二进制或固件。
不分发上游应用、界面、图片或三星品牌资源。

日常设置继续核对同一 `754b6fdf` 提交：原版 Buds 的 `FE/EE` 帧、Buds+ 标识、EQ、
环境声与手势，以及用户主动查找。现代九段自定义 EQ 的发送值与设备确认的 EQ 模式
分别记录；来源明确部分固件读回平坦曲线，本实现不将已发送草稿伪装成设备确认曲线。

## PuddingPods release interoperability

- [lingbai-rong/PuddingPods](https://github.com/lingbai-rong/PuddingPods)，发布包 v3.47；
  仓库 README 声明 GPL-3.0，所检仓库未包含应用源码。
- APK SHA-256：`EBE7C11AB40E988BB24AE765F736E1FDD4F9B929E8CBF4EFC64D57D83EEB4AE0`。

依据发布 APK 的执行路径独立整理完整载荷长度方言、五态噪声、反向增益枚举及 LED
读写，不把文档枚举当作实际字节，不复制 APK 的 Settings 重定向、界面或厂商资源。
原有捕获验证路径继续保留，新的控制须在本次会话取得无歧义的对应方言回报。

## Android platform APIs

- Android Open Source Project: <https://source.android.com/>
- libxposed API: <https://github.com/libxposed/api>

## Miuix

- compose-miuix-ui/miuix: <https://github.com/compose-miuix-ui/miuix>
- Reviewed release: `v0.9.3` (`c36fab72391801d1e3ea5a00f966bf16bac28d4c`)
- Version used by HyperEars: `0.9.3`
- Upstream license: Apache License 2.0

用于提供 Miuix 应用界面、菜单栏背景捕获与模糊能力。应用状态、导航、设置、耳机会话及
MiLink 集成仍由 HyperEars 自身的共享层维护。

## KernelSU UI reference

- tiann/KernelSU: <https://github.com/tiann/KernelSU/tree/4521784328352c54334beb29e05c74360b60d7cb>
- Upstream license: GNU GPL-3.0

界面设置的独立页面、实时预览和界面缩放参考 KernelSU Manager。Miuix 悬浮底栏基于上述
固定提交中的 `FloatingBottomBar`、动画和手势结构适配，统一维护图标、文字、选中胶囊与
拖动交互。HyperEars 仅接入自身三个顶层页面，未引入 KernelSU 的 Root 管理业务、品牌资源
或应用状态代码。

## DexKit

The optional MiLink compatibility resolver uses DexKit to locate one verified semantic method
fingerprint when stable symbols and the exact-version table are unavailable.

- Project: <https://github.com/LuckyPray/DexKit>
- License used by HyperEars: Apache License 2.0 (selected from the artifact's published
  Apache-2.0/LGPL-3.0 license metadata)
- Scope in HyperEars: one bounded, unique-result DEX query during initialization of an otherwise
  unsupported MiLink build; it is not used by Bluetooth protocol sessions or card interactions.

各依赖的二进制和许可证信息由 Gradle 依赖元数据及其上游项目提供。

## Honor X5s Pro community contribution

荣耀亲选耳机 X5s Pro（BTV-ME10）的 SPP 帧样本、字段映射与实机验证由 Te_River 在
[PR #23](https://github.com/silverpoetry/HyperEars/pull/23) 提供。HyperEars 在项目现有的
Adapter、ProtocolSession 与 WireCodec 边界内合并和维护该贡献；不随项目分发厂商应用、
抓包文件、固件或品牌资源。该贡献合入后的源代码遵循本项目的 GNU GPL-3.0-only 许可。

## ROSELINK application research

- ROSELINK 3.8.2/code 5409，包名 `cn.ikaile.ruoshui.client`。
- 研究依据为用户设备中的本地 APK；SHA-256：
  `815fa535c2a518479fbd4dd52e8b95fd288692ec8a3299250ba77838630c6075`。

Coral 的独立实现使用静态分析确认的接口事实：RS Common V2 收发封装、状态字段、
控制参数、音频选项和 AB OTA 消息结构。型号与功能清单见
[ROSELINK 调研](docs/research/source-audits/roselink-model-feature-inventory.md)，
升级细节与尚未确认的边界见 [AB OTA 规格](docs/research/source-audits/rose-coral-ota-static-spec.md)。
不分发厂商应用、反编译产物、厂商代码、固件或商标图片；应用及相关资源的权利属于
其权利人。静态协议依据与真实设备验证分别记录。

## MOONDROP Link application research

- MOONDROP Link for Android（`com.moondroplab.moondrop.moondrop_app`），官方下载
  <https://download.moondroplab.com/moondroplink/android-release.apk>
- 分析版本：官网 2.25.0c（未混淆构建）与 2.26.2c（混淆构建，用于常量交叉核对）

仅为互操作性目的分析该闭源厂商应用，提取接口事实：MOONDROP GAIA 线协议族的 QTIL
厂商 ID 29、feature/type/command 字编码、API 版本握手（vendor 10、`0x0300`）、电量
组件表（左/右/盒）与标准 SPP 端点。ZZZ-ANGELS-OWS 适配据此独立实现，并与该 App 同
设备实机抓包交叉验证。HyperEars 只记录可互操作的协议事实，不复制任何代码，不分发
厂商应用、反编译产物、固件、图片或品牌资源；厂商应用的权利归成都水月雨科技有限公司
所有。

## FloS2 community protocol contribution

TheAstro OpenPro S2 的 GATT 帧、CRC、整机电量、四档音效、脱敏测试向量与原版实机结果由
Conastin 在 [PR #90](https://github.com/silverpoetry/HyperEars/pull/90) 提供；Dreame Flo S2
按贡献者资料作为家族候选。维护分支保留其四个原始提交，源代码及贡献者绘制的模式图标按
本项目 GNU GPL-3.0-only 许可维护。协议资料来自贡献者对伴侣 App 的互操作研究和实机记录，
不分发厂商应用、固件、反编译产物或厂商图片。伴侣 App 的名称、包名和版本待补充核对。

所有商标、产品名称和厂商应用名称仅用于描述兼容性，归其各自权利人所有。

### Additional ROSELINK RS Common v2 profiles (2026-10-28)

ROSELINK 3.8.2 (5409), matching Dart 3.11.4 ARM64 AOT, is a behavioral protocol reference for nine additional manufacturer-prefix-qualified profiles. Independently reconstructed facts cover model IDs, transport framing, report fields and bounded host integration. No vendor implementation, assets, APK or firmware is distributed. Exact source offsets and qualifications: `docs/rose-rsv2-protocol.md`; existing Coral attribution remains unchanged.
