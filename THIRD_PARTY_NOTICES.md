# Third-party notices and research sources

HyperEars 的原创代码整体按 GNU GPL-3.0-only 发布。以下项目为协议研究、行为对照或
架构参考来源；HyperEars 不随 APK 分发它们的应用、反编译产物或品牌资源。

## OPPOPods

- 1812z/OppoPods: <https://github.com/1812z/OppoPods>
- Leaf-lsgtky/OppoPods: <https://github.com/Leaf-lsgtky/OppoPods>

用于理解 OPPO 私有 RFCOMM 帧、电量、降噪和 HyperOS 系统集成路径。1812z 项目
README 声明 GPL-3.0。HyperEars 不随 APK 分发该项目的代码、应用或资源；本项目许可证
及其与外部资料的关系以各自仓库中的许可证原文为准。

## HyperRose / ROSESELSA protocol profiles

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
组件电量通知和降噪状态。HyperEars 只实现 MiLink 所需的最小 AAP 子集，不使用或分发
上述项目的名称、图标、图片、界面和其他品牌资产。

## NiceHCK Controller

- ZaeXT/NiceHCK_Controller:
  <https://github.com/ZaeXT/NiceHCK_Controller/tree/f1348a8d09fc57e3b7098a4f15bae5f926475771>
- Upstream license: MIT

用于核对 NiceHCK/YuanDao OriG in 的 RFCOMM UUID、帧长度、操作码、电量字段和噪声
模式枚举。保留其上游 MIT 声明如下：

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

## ScrewVivoTWS

- moculll/ScrewVivoTWS: <https://github.com/moculll/ScrewVivoTWS>

用于 vivo TWS 3e 的公开协议画像。该仓库当前未被 GitHub 识别出标准许可证；本项目
只记录可互操作的协议事实和独立实现，不复制其程序或资源。

## Bose BMAP research

- aaronsb/bosectl: <https://github.com/aaronsb/bosectl>
- danielgjackson/noisecancel: <https://github.com/danielgjackson/noisecancel>
- Upstream licenses: MIT

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
`0x0C`、厂商数据 `0x521C` 及伴生控制端点关联方式。HyperEars 只实现 MiLink 所需的电量
与原生三态噪声控制，并在自身 Adapter、ProtocolSession 和 WireCodec 边界内重新组织实现；
不分发 QCYpods 的应用、界面、图片、品牌资源或构建产物。上游许可证状态按固定提交如实
记录，具体条款以其仓库声明为准。

## OpenFreebuds (Huawei protocol research)

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

## Android and LSPosed APIs

- Android Open Source Project: <https://source.android.com/>
- libxposed API: <https://github.com/libxposed/api>

## Miuix

- yukonga/miuix: <https://github.com/miuix-kotlin-multiplatform/miuix>
- Version used by HyperEars: `0.9.3`
- Upstream license: Apache License 2.0

用于提供可选的 Miuix 应用界面。HyperEars 只复用该库公开的主题与 Compose 控件 API；
应用状态、导航、设置、耳机会话及 MiLink 集成仍由 HyperEars 自身的共享层维护。

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

所有商标、产品名称和厂商应用名称仅用于描述兼容性，归其各自权利人所有。
