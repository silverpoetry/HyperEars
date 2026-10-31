# OnePlus OPO 家族适配

## 1. 范围与证据

本适配复用现有 OPPO OPO 会话与编解码器，仅提供系统电量回退、协议电量和降噪/关闭/通透。
EQ、手势、编码、双设备及其他详细配置交给欢律等控制 App，遵循
[项目范围](project-scope.md)。

- [Issue #94](https://github.com/silverpoetry/HyperEars/issues/94) 的 OnePlus Buds 4，固件
  138.138.105、欢律 17.6.5，强制选择 `oppo-enco-family` 后已确认三态控制正常。
  导出日志包含 `oppo-private-rfcomm` 的查询、设置与模式回读，并有左右耳/盒字段。
- Buds 4 的自动名称识别是本维护版新增部分，待该设备复核；家族其他型号按外推记录，
  不将一个型号的测试结果扩展为全品牌实测。
- 公开 Buds 4 与 Buds Pro 3 实现使用相同经典 RFCOMM UUID 和核心三态命令。
  Nord 等资料另外涉及 BLE、注册与模式配置，不能仅凭同一 UUID 合并所有产品线。

## 2. 匹配与继承

```text
OpoProtocolAdapter（共享协议、端点与控制 App）
  ├─ OppoEarbudAdapter（OPPO / Enco 品牌家族）
  └─ OnePlusEarbudAdapter（OnePlus / 一加品牌家族）
       └─ OnePlusBuds4Adapter（精确型号）
```

匹配仅检查现有系统耳机身份、原生系统保护和规范化名称，不在匹配阶段发包。

| 范围 | 规则 |
| --- | --- |
| Buds 4 | `OnePlus Buds 4` / `一加 Buds 4` 的规范化完整名称优先匹配具体型号 |
| Buds / Buds Pro | 完整品牌与产品线名称，可带数字代际；`Buds Pro 3` 与 `Buds 3 Pro` 均属于家族候选 |
| Nord、Z、Ace、Bullets、LEA 或 Case 后缀 | 不进入此经典家族，继续标准回退，待各自端点与配置证据 |
| 未知品牌但有 `079a` UUID | 不据此判定 OnePlus，继续现有品牌匹配或标准回退 |

RFCOMM UUID `0000079a-d102-11e1-9b23-00025b00a5a5` 是共享协议端点，不是 OnePlus 品牌凭据。
本轮不新增 BLE 扫描、固定 RFCOMM 通道、认证令牌或等待时间。

## 3. 确认与控制

所有候选初始保留系统整机电量，不开放私有噪声模式。复用共享会话的只读查询：

1. 通知支持响应确认协议及可订阅项，不单独开放电量或降噪；
2. 合法电量响应开放实际回报的组件电量；
3. 可解释的模式响应开放标准三态，控制后查询设备状态。

| 控制 | 核心设置帧 |
| --- | --- |
| 关闭 | `AA 0A 00 00 04 04 F0 03 00 01 01 01` |
| 降噪 | `AA 0A 00 00 04 04 F0 03 00 01 01 02` |
| 通透 | `AA 0A 00 00 04 04 F0 03 00 01 01 04` |

上述三条与 #94 实测写入一致。读取到降噪强度或自适应档时沿用共享会话的 ANC 投影，
不增加档位编辑或抗风噪控制。传输、遥测及回退继续使用现有流程；独立品牌 ID 不复制协议对象，
每台设备仍创建自己的 Adapter 与 ProtocolSession。

设置导航与退避继承 OPPO 的控制 App 声明，顺序为欢律、Wireless Earphones、旧版 ColorOS
Wireless Earphones。包名和作用域已在[控制 App 目录](control-apps.md)中登记。

## 4. 来源与验证

- HyperEars [#94 的复核反馈与脱敏日志](https://github.com/silverpoetry/HyperEars/issues/94#issuecomment-6524319442)。
- [OP-Buds-4-PC-Controls](https://github.com/Megalodon-sharky/OP-Buds-4-PC-Controls/tree/de25f9eda94da80234c9f8dfb1112fd2fd8c1d25)
  （MIT）：核对 Buds 4 端点及标准三态短写入的兼容性；该工程的 Buds Pro 3 配置标为参考实现。
- [OnePlus Buds Pro 3 工具](https://github.com/nic0manz/oneplus_buds3_pro_python/tree/faec7e5fe66acba7b89a907610ba1d0c86bdbba9)：
  核对产品线与 RFCOMM 实现资料，仓库未发现明确许可证，仅作为协议事实参考，不复制源码。
- [OnePlusBudsManager](https://github.com/Sarfraz-droid/OnePlusBudsManager/tree/de6625c1102369e30cdf883b56777cd44d0fdde9)：
  核对 Nord Buds 4 Pro 的 BLE 路径及显式方言选择；该产品线不纳入本维护版。
- 既有 OPPO 编解码来源和格式见[OPPO 协议说明](oppo-enco-protocol.md)及
  [第三方声明](../THIRD_PARTY_NOTICES.md)。

新增测试覆盖精确/家族选择、歧义名称与系统设备排除、共享 UUID 的品牌隔离、品牌关闭、
系统电量保留、逐项能力门禁、会话隔离和三态读写。该验证不替代新增自动识别入口的最终实机复核。
