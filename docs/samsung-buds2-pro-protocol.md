# Samsung Galaxy Buds 协议记录

## 范围与证据

本适配以 Galaxy Buds2 Pro（SM-R510）为实测基线，协议参考
[GalaxyBudsClient 固定提交](https://github.com/timschneeb/GalaxyBudsClient/tree/754b6fdfb1c151b022cece775c8aaad4eb185afa)。
来源与许可见 [第三方声明](../THIRD_PARTY_NOTICES.md)。

贡献者在 2026-10-04 报告原版 Fix25 的 Buds2 Pro 实测通过。当前维护版改变了型号匹配、
卡片绑定与逐耳状态处理，最终维护包仍待实机复核。Buds2、Buds FE、Live、Pro、Buds3、
Buds3 Pro、Buds4 和 Buds4 Pro 按参考实现接入；Core 与 Buds3 FE 的参考客户端仍有布局
TODO，本项目只采用共同字段，属于实验性家族外推。各型号须分别验证。

2026-10-23 维护者批准按当前证据范围合入 #87，保留 #74 / #83 的作者提交。
此次放行不代表维护版或其他型号已完成实机复核，后续设备反馈继续按下文清单跟踪。

## 识别与确认

官方 Galaxy Buds / Samsung Galaxy Buds 名称及 SM-R 编号选择具体型号候选；
官方名称可带四位十六进制设备后缀。自定义名称中的型号提示须同时具备三星专属服务。
仅具备专属 UUID 的未知型号选择家族回退；标准 SPP UUID 本身不证明三星身份。

候选 Adapter 保留 Android 系统整机电量、音量和流转。CRC 有效的设备状态逐项确认私有
电量、噪声和设置；协议断开或让出控制权时清除私有状态。控制 App 只负责导航与退避，
不参与型号识别。Live / Pro 使用标准 SPP，其余型号使用以下专属服务。

## 传输与帧

- RFCOMM 服务 UUID：`2e73a4ad-332d-41fc-90e2-16bef06523f2`
- 帧：`FD [size:2LE] [message id] [payload] [crc16:2LE] DD`
- `size` 包含 message id、payload 和 CRC；最低为 3。
- CRC 为 CRC16-XMODEM（多项式 `0x1021`、初值 `0`），覆盖 message id 与 payload。
- 解码器按连续字节流工作，处理拆包、粘包、前导噪声、非法长度和错误 CRC；只有完整且校验
  通过的状态帧可以成为 Adapter 的能力证据。

## 已接入消息

| ID | 方向 | 用途 |
|---:|---|---|
| `60` | 耳机 → 模块 | 基础状态与组件电量 |
| `61` | 耳机 → 模块 | 扩展状态、电量与噪声模式 |
| `42` | 耳机 → 模块 | 写入确认；携带原命令 ID 与设备确认参数 |
| `77` | 耳机 → 模块 | 降噪状态更新 |
| `78` | 模块 → 耳机 | 降噪 / 关闭 / 环境声 |
| `88` | 模块 → 耳机 | 管理器客户端信息 |

## 卡片与生命周期

MiLink 卡片按型号显示原生噪声模式。其他耳机设置由 Galaxy Wearable 管理。

Galaxy Wearable 及各型号控制插件在统一控制 App 目录声明。“停止厂商应用”从该目录生成
包名列表，覆盖所有声明的控制器。退出退避、会话重建与其他 Adapter 共用既有生命周期。

## 最终维护版复核

请记录耳机固件、HyperOS、Android 和 MiLink 版本，验证：

1. 连接后电量、噪声模式逐项开放；未回报字段保持关闭。
2. 在卡片切换噪声模式，确认耳机生效并回报正确状态。
3. 将「更多设置」目标设为厂商 App，检查 Galaxy Wearable 跳转及应用不可用时的回退。
4. 检查三星卡片仅提供该型号支持的噪声模式。
5. 厂商 App 启停、退避和恢复正常，“停止厂商应用”覆盖对应型号插件。

HyperEars 设置 → 调试中开启“详细日志”；LSPosed 关闭“禁用详细日志”并开启
“输出日志到守护进程”。复现后使用 HyperEars 的“导出日志”保存记录。
