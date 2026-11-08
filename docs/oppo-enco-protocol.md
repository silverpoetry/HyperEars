# OPPO Enco 协议盲适配记录

## 1. 资料边界

本适配依据开源项目 `1812z/OppoPods` 中公开的协议事实重新实现，尚未在当前
HyperEars 测试设备上逐型号抓包确认。因此这里区分两类结论：

- 已由参考实现明确给出的帧格式、命令、UUID 和型号例外；
- HyperEars 为接入统一状态模型所做的保守映射。

没有公开依据的游戏模式、均衡器、空间音频、双设备连接和佩戴检测暂不接入
MiLink，也不会发送对应写命令。

## 2. 识别与端点

OppoPods 没有通过私有协议查询产品 ID，而是对已连接蓝牙设备的名称做
不区分大小写的 OPPO 判断，再按归一化后的完整型号名称选择候选线配置。

HyperEars 保持同一成本等级：

1. 只处理 Android 已确认是耳机且不是小米原生耳机的设备；
2. 名称包含 `oppo` 或 `enco` 时进入 OPPO 家族；
3. `OPPO Enco Air2 Pro`、`Free4`、`X3`、`Air5` 优先进入具体 Adapter；
4. 其余 OPPO/Enco 设备进入通用 OPPO Adapter；
5. 不匹配 OPPO 时继续落到标准蓝牙耳机。

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

1. 查询支持的通知 ID（`0x0200`）；
2. 查询电量；
3. 查询当前降噪状态；
4. 收到 `0x8200` 后过滤调试 ID，并通过 `0x0205` 一次性订阅其余通知。

这不是轮询。控制写入后仅做一次 `0x010C` 权威回读，日常电量和模式变化由
耳机 `0x0204` 主动报告驱动。

## 7. Adapter 层级

```text
StandardEarbudAdapter
  └─ OpoProtocolAdapter (共享经典 OPO 协议)
       ├─ OppoEarbudAdapter (OPPO/Enco 家族入口)
       │    ├─ OppoEncoAir2ProAdapter (兼容 ANC 编码)
       │    ├─ OppoEncoFree4Adapter
       │    ├─ OppoEncoX3Adapter
       │    └─ OppoEncoAir5Adapter
       └─ OnePlusEarbudAdapter (OnePlus 家族入口)
```

四个具体型号保留独立 ID，但名称只决定候选线配置。所有 OPPO Adapter 初始都只发布
系统整机电量与流转能力；通知支持响应只确认协议并建立订阅，合法电量响应开放私有组件
电量，合法 ANC 状态响应才开放降噪、关闭和通透。当前具体型号除 Air2 Pro 的编码例外外，
均复用家族 `ProtocolSession`。

OnePlus 的经典 OPO 候选在独立品牌组登记，与 OPPO 继承同一协议基类及控制 App 配置，
不把共享 UUID 作为品牌判断。新增范围与验证边界见[OnePlus OPO 家族](oneplus-opo-protocol.md)。

## 8. MiLink 边界

OPPO 设备复用 HyperEars 已有的 TWS 官方载体 ID 和 MiLink 原生三态卡片。
本次适配没有新增：

- 混淆类/方法 Hook；
- View ID 或卡片布局 Hook；
- 设置页注入；
- 扫描、定时器或轮询任务。

资料来源：

- <https://github.com/1812z/OppoPods>
