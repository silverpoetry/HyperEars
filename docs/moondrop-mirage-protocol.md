# MOONDROP MIRAGE 协议适配

HyperEars 为 MOONDROP MIRAGE 提供**部分实机验证**的适配：握手、私有电量（左右耳与充电盒）
与噪声三态读写已于 2026-09-21 实机会话确认；「降噪」写入的默认档位与部分字段语义待补证
（见 §7.3）。MIRAGE 的零售身份为
HATSUNE MIKU × MOONDROP 联名 TWS（以官方渲染图确认）；Adapter 显示名使用
`MOONDROP MIRAGE`，Bluetooth 广播名经实机官方 App 记录与 HyperEars 实机会话双重确认
（精确匹配臂命中，见 §1 与 §7）。帧格式、握手、电量与噪声模式
沿用已在 Pudding 实机确认的帧集，本文 §2–§5 为同构引用并逐项标注实机结果。
本文未验证的行为一律不表述为「已验证支持」。

## 1. 判型与平面推断

服务端 2026-09-16 `products/all` 快照（参考来源，见 §7.2）记录的型号事实：UUID
`e9aa2b61-74d5-451f-995d-254d3a9e6666`、名称 `MOONDROP MIRAGE`、`chipType` `jieli`、
形态 BT-TWS、EQ 10 段、含 OTA 配置；`relations/uuids` 唯一关联型号为 MOONDROP Pudding
（UUID `291a21ef-bca1-4cf1-baf8-151c457bf082`）。

平面推断链：同 `chipType`（jieli）且唯一同族 sibling 为 Pudding
（HyperEars 已实机验证的 FF-SPP 帧面），因此 MIRAGE 最可能走 FF-SPP/jieli 平面。
实机官方 App 连接记录与该推断一致：设备以真实 MAC 登记于经典蓝牙面，且其 SN 字段
命名与 APK 的 BLE identity 面命令吻合（SPP 控制面 + BLE identity 面并存，见 §7.2）。
BLE GATT `9ECA…` 控制平面在服务端目录与实机记录中均无证据，本适配不对其作宣称。

Adapter 只在下列名称规则之一成立时选择 MIRAGE。归一化 = 转小写并仅保留字母与数字
（与 Pudding 相同）。联名身份使别名集覆盖双品牌族，且品牌 token 强制共现以防泛词误中：

- 归一化完整名称为 `moondropmirage`；
- 名称同时包含 `moondrop` 与 `mirage`；
- 名称同时包含“水月雨”与 `mirage`；
- 名称同时包含 `moondrop` 与 `hatsunemiku`（覆盖 `HATSUNE MIKU × MOONDROP` 的归一化
  形态）。

其中 `moondrop` + `hatsunemiku` 共现规则不要求 `mirage` token，系有意为之的已知设计
决策：零售联名广播名可能只带双品牌 token 而不含 "MIRAGE" 字样，收紧该臂反而会漏判
真机。已知副作用：未来若推出第二款 MOONDROP × 初音联名型号，将先命中本 Adapter
候选而非走家族回退；爆炸半径有界——握手失败即 `KeepDormant` 能力全锁、电量沿用
Android 系统整机聚合，不开放任何私有能力。第二款联名临近时须复核并收紧此臂。

负例：`Desired Mirage 500` 与 `MIKU Speaker` 不含水月雨品牌 token，不进入 MOONDROP
适配；`MOONDROP Xyz` 只命中家族回退。服务端目录不携带 MAC、OUI 或传输层字段，因此
名称归一化与后续协议响应证据是仅有的两道判型门；广播名精确串经实机官方 App 记录佐证
为 `MOONDROP MIRAGE`（§7.2），中文别名形态仍待实机确认。

传输使用 Bluetooth SIG 标准 SPP UUID `00001101-0000-1000-8000-00805f9b34fb`。该 UUID
被大量蓝牙设备共同使用，只负责建立 RFCOMM 端点，不属于水月雨身份依据，也不会让
其他 SPP 耳机进入 MIRAGE Adapter。

## 2. 帧格式（同构引用，待本型号复核）

FF 帧的字段布局、分片与尾字节处理见
[`moondrop-pudding-protocol.md` §2](moondrop-pudding-protocol.md#2-帧格式)。MIRAGE 复用
同一个 `MoondropPuddingWireCodec`（未新建 codec）；该帧集来自 Pudding 实机捕获与
Robin 公开协议，MIRAGE 自身尚无帧捕获，待 §7.3 复核。

## 3. 握手（同构引用，待本型号复核）

```text
发送：FF 01 00 00 00 0A 03 00
响应：FF 04 00 04 00 0A 83 00 00 04 03 01
```

完整校验规则见 [`moondrop-pudding-protocol.md` §3](moondrop-pudding-protocol.md#3-握手)。
名称只选择候选；只有 MIRAGE 本机给出完整合法握手响应，私有协议才进入确认状态。
握手失败时 Adapter 保持登记但能力全锁（`KeepDormant`，统一有界退避），不改判为其他
品牌，也不开放任何私有能力。

## 4. 电量（同构引用，待本型号复核）

查询命令与响应布局见 [`moondrop-pudding-protocol.md` §4](moondrop-pudding-protocol.md#4-电量)。
握手确认后在连接生命周期内查询电量与噪声模式；不创建常驻轮询。协议确认前电量沿用
Android 系统整机，收到合法电量帧后切换为私有左右耳与充电盒。连接初期分侧回报的合并
与补齐使用与 Pudding 相同的有界 bootstrap 常量（`500/800/1200/1600 ms`，移植自 Pudding
行为，MIRAGE 上的暂态时长未精确校准；达到边界即接受最后一次真实回报，只会延迟、不会
制造错误值）。左右耳 `00`/`FF` 与充电盒 `FF` 的“未连接/不可读”语义同样待本型号复核。

实机结果（2026-09-21）：握手确认后私有左右耳与充电盒电量正常上报（红摘设备报告见 §7.1）；
bootstrap 期间未观察到错误值。

## 5. 噪声模式（同构引用，待本型号复核）

三态（关闭/降噪/通透）的查询、设置帧与回显确认见
[`moondrop-pudding-protocol.md` §5](moondrop-pudding-protocol.md#5-噪声模式)。降噪能力仅由
`1D 40/41` 的合法响应逐项门控；写入使用 `PUBLISH_AFTER_WRITE` 与 `600 ms` 初始回查加
`500/700/900/1200 ms` 有界确认序列。快照记录的 10 段 EQ、`isDisplay:3` 等字段不映射为
任何代码能力（语义未知且当前框架无 EQ 能力面）。

实机结果（2026-09-21，部分）：设备对 `1D 40/41` 给出合法响应，三态读写解锁并被设备接受。
观察到的型号差异：HyperEars 按 Pudding 语义写入的「降噪」档，在官方 App 中显示为
**基础降噪**；该设备另有**自适应降噪**等更多档位（官方 App 按 MAC 缓存的模式值为 `4`，
APK `AncV2Handler` 校验模式 `0..5` 共六档，档位标签在无法静态解析的 Dart 层）。自适应档
对应的帧字节须待实机抓包确认——在此之前 HyperEars 不宣称、也不发送自适应档；把「降噪」
默认档位调整为自适应是本仓库已知待办（§7.3-4）。

## 6. 代码边界

- `MoondropMirageAdapter`：名称判型、联名别名集、传输候选、能力门禁与其有界状态；
- `MoondropMirageProtocolSession`：握手进度、遥测查询、控制编码和回读；
- `MoondropPuddingWireCodec`：复用，不新建 codec；纯字节帧、流式解码和字段校验，
  Decoder 实例每会话独立；
- `MoondropModelCatalog`（protocol 模块）：离线蒸馏快照表（镜像 `BoseProductCatalog`
  先例），仅用于型号数据对账与测试，不参与设备匹配，运行时零外联；
- MiLink：只读取 Adapter 确认发布的标准电量与噪声状态，不增加自定义卡片。

## 7. 来源与证据

### 7.1 实机验证

- 2026-09-21 HyperEars 调试构建实机会话（设备地址红摘为 `C1:5B:CF:**:**:**`）：广播名
  `MOONDROP MIRAGE` 精确匹配臂命中；RFCOMM 私有通道建立；FF 握手给出合法响应（协议确认）；
  私有左耳/右耳/充电盒电量正常上报；噪声三态查询与写入解锁并被设备接受。观察差异：
  「降噪」写入在官方 App 显示为基础降噪档（§5）。
- 官方 App 连接记录（2026-09-20 导出，红摘）佐证身份与平面事实（§7.2），属 App 侧证据，
  不替代帧级复核。

### 7.2 参考协议来源

- 服务端快照：`GET https://cdn-service.moondroplab.tech/api/v1/products/all`，抓取日期
  2026-09-16；本型号 UUID 见 §1；原始 JSON 响应未入库；
- 实机官方 App 数据记录（2026-09-20 从用户设备导出，红摘；数据目录含真实 MAC、
  SN 与登录态，未入库）：官方 App 连接记录中 `name` 与 `deviceModel` 均为
  `MOONDROP MIRAGE`，UUID 与 §1 目录一致，固件版本 `3.5.2`，SN 为 20 位数字（左右耳
  各一，红摘）；App 缓存的服务端 `funcList` 为 15 项 `/jieli` 模块清单（含 eq、peq、
  ancV2、touchV2、led、onebringtwo、lhdc、ota、voicecontrol 等）——该清单是 App 模块
  列表而非帧真值，本适配据此维持保守降级，不开放任何清单内新能力面；OTA 配置载体
  扩展名为 `.ufw`；官方 EQ 预设仅「标准」；联名标识（MIKU/HATSUNE/初音）在设备数据
  中零出现，广播名以 `MOONDROP MIRAGE` 为准；
- 噪声档位旁证：官方 App 按 MAC 缓存的模式值 `ANC_AWN_V2=4`（导出时用户所选档），与 APK
  `AncV2Handler` 的模式 `0..5` 六档校验共同表明设备控制面大于三态；档位标签→帧字节的映射
  未获得（标签在合并快照 Dart 层，静态不可解析），待实机抓包。
- `MOONDROP.apk` `classes.dex` 的 baksmali 2.5.2 蒸馏（仅互操作研究，APK 不入库）。
  关键样例：`BleSourceSwitchFrames.smali:870`（`0xA5` 帧魔数）、`QTILFeature.smali:1003`
  （`ANC_V2 = 0x20`）、`FactorySppProtocol` array-data（`55 AA 43 58 57`）。注意：APK 字符串
  层对 `MIRAGE` 零命中，Java 层亦无 FF 帧复现，因此上述 smali 证据只用于登记平面方法
  与边界（并支撑 §8 禁令），不构成 MIRAGE 帧真值；
- FF 帧真值来源：Pudding 2026-08-15 实机捕获与 Robin 公开协议文档，见
  [`moondrop-pudding-protocol.md`](moondrop-pudding-protocol.md) §7 与
  [`moondrop-robin-protocol.md`](moondrop-robin-protocol.md) §7；
- sibling 推断链（§1，推断）。

### 7.3 待实机清单

1. 已完成（2026-09-21）：广播名 `MOONDROP MIRAGE` 精确匹配臂实机命中；中文/联名别名的
   实际归一化形态仍待确认（臂 2–4 保留）；
2. 已完成（2026-09-21）：握手请求与合法响应实机捕获，平面推断成立；
3. 已完成（2026-09-21）：私有左/右耳与充电盒电量实机上报；分侧 `00`/`FF` 与充电盒 `FF`
   的未连接语义细节待补充捕获；
4. 部分完成：三态写入与回读实机解锁；「降噪」写入实测落在基础降噪档，**自适应降噪档的
   帧字节待实机抓包确认（目标：将 HyperEars 的「降噪」默认档调整为自适应）**；
5. 待精确校准：bootstrap 期间未观察到错误值，暂态时长边界未逐档实测；
6. 部分完成：官方 App 打开后 HyperEars 按进程级所有权语义让渡私有通道；仅关闭 App 界面
   （进程存活）不触发收回——与设计的让渡语义一致；强行停止或重连后的收回路径待复核；
7. 待确认：快照字段（`isDisplay:3`、EQ 10 段、OTA 面）的实际设备语义。

## 8. FactorySpp 产测协议不可适配

蒸馏证据记录了一个产测通道：帧为
`55 AA 43 58 57 | 00 | LE16(payload+4) | LE16(type) | LE16(payloadLen) | payload | CRC16 LE`，
CRC 为 CRC-16/IBM 反射（poly `0xA001`，出处 `crc16Ibm:321-458`、`buildFrame:116-320`）；
type `GET_SN = 0x16`、`SET_SN = 0x15`，SN 为 20 个可打印 ASCII；另有 10 字节裸 mic 检测串
`09 0B CC 55 30 37 09 00 00 00`。该协议属于产线测试与写 SN 面，与消费端耳机控制无关：
**HyperEars 明确不适配 FactorySpp，不连接该端点，也不把它用作任何回退控制或判型证据。**

HyperEars 根据可互操作的协议事实独立实现，不分发厂商 App、固件、反编译产物、
图片或上游程序。
