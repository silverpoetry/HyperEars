# 问题排查

## 卡片完全不出现

1. 确认耳机在系统蓝牙中已连接并可播放声音；
2. 确认 LSPosed 启用了 `com.android.bluetooth` 和 `com.milink.service`；如果需要控制 App
   退避，再按[控制 App 目录](control-apps.md#2-当前目录)确认对应作用域已启用；
3. 确认安装版本满足 Android 15+ 与 LSPosed API 101；
4. 重启整台设备，而不是只重启 HyperEars 应用；
5. 打开运行看板，检查是否存在对应地址的设备会话。

如果没有会话，通常是设备未被保守判定为耳机、型号/服务未命中，或 Bluetooth 进程
未加载模块。存在会话但 MiLink 的“状态接收”长期未观测时，重点检查 MiLink 进程是否
加载模块，并核对卡片 Adapter ID 与安装版本。

## 卡片只有音量

只有音量可能是正常回退，也可能是私有能力尚未就绪：

- 标准蓝牙耳机本来就只发布系统电量和音量；
- 需要私有 GATT、RFCOMM 或 BR/EDR L2CAP 的型号必须先完成“私有通道”和“协议确认”；
  名称、服务 UUID 或具体型号配置只选择候选协议，不会直接开放私有控制；
- 快速反复展开卡片时，MiLink 可能先用基础快照创建界面，后续状态应触发原生刷新；
- 如果同一会话长期没有恢复控制按钮，采集看板 revision 和 MiLink 日志。

## 模式切换成功但卡片没有更新

确认耳机是否返回了状态报告。HyperEars 优先以设备回报为权威；只有明确声明即时确认
策略的型号才会在写入后立即更新。如果耳机已切换而 UI 未变，请记录：

- 点击前后的看板 revision；
- 耳机实际声音变化；
- 卡片当前选中项；
- 同时段 `HyperEars` 日志。

## 多耳机连接时只有当前音频设备有状态卡

MiLink 融合设备中心只为当前音频输出设备渲染完整的耳机状态卡（电量、模式等）；其他已
连接耳机在 MiLink 中只有简单设备条目，不显示电量。这是 MiLink 自身的呈现行为：将
HyperEars 换回官方 Release 或直接停用模块后，非活跃耳机的表现完全相同。HyperEars 侧对
每台耳机的识别、私有协议会话和电量应答均按设备地址独立工作，可在 HyperEars 主页按设备
分别确认；设备切回活跃后状态卡立即恢复。

## “更多设置”闪退或打开错误页面

当前实现从 MiLink 的语义控制器边界读取真实蓝牙地址，再按“更多设置”的选项打开系统设备
详情、厂商 App 或 HyperEars。选择“系统设置”时会打开 HyperOS 的
`BluetoothDeviceDetailsFragment`；若 ROM 更改了 Settings Intent 或 Fragment 参数，模块会
回退到蓝牙设置列表。提交问题时请附 Settings 崩溃堆栈和 ROM 完整版本。

MiLink 入口优先按稳定类名和已验证版本表解析。未知版本仅在进程初始化时执行一次严格 DEX
语义查询，并且只有唯一方法同时满足参数、返回值、精确日志常量和异步调用特征时才启用；查询
失败或出现多个候选时保持系统原行为，不扫描 View、不遍历布局，也不在每次点击时重试。

选择“厂商 App”后，模块只会打开当前 Adapter 在
[控制 App 目录](control-apps.md#2-当前目录)中声明、已经安装且具有 Launcher Activity 的
候选 App。没有可启动候选时仍进入真实蓝牙设备详情。App 页面跳转本身不依赖 LSPosed
作用域；未勾选作用域只会导致该 App 无法登记进程状态、不能触发运行时退避。

## 厂商控制 App 不退避或退出后不恢复

依次确认：

1. HyperEars 中“运行时退避”已经开启；
2. LSPosed 为实际安装的包名启用了 HyperEars 作用域，而不是仅按桌面名称选择相似 App；
3. 修改作用域后已彻底重启对应 App 进程；
4. 看板是否显示“专有控制 App 运行中”；该状态表示 Binder 登记有效，不表示 App 已连接耳机；
5. 退出时确认该 App 的所有进程已经结束。返回键、返回桌面或划走最近任务都不保证进程退出。

需要立即释放控制权时，手动强制停止控制 App，或使用 HyperEars 设置页中需要 Root 的
“停止厂商应用”。若控制 App 没有启用作用域，模块无法获得进程存活回执，也不会进入退避。
完整条件和包名见[控制 App 作用域文档](control-apps.md)。

如果不希望模块在厂商 App 运行时退避，可在 HyperEars“设置”中关闭“运行时退避”。
这不会阻止厂商 App 自己连接耳机，只会让 HyperEars 继续维护自己的私有协议通道；同时连接
两个控制端可能造成协议争用，只有在确有需要时才建议关闭。

## 流转超时

先区分耳机协议通道与 MiLink 设备共享通道：HyperEars 的 GATT、RFCOMM/L2CAP 通道只
负责耳机电量/模式，不承载跨设备音频流转。若两个方向表现不一致：

1. 确认两台设备都运行同一 HyperEars 版本；
2. 确认两端 MiLink 和系统蓝牙均正常；
3. 记录流转发起端、目标端、方向和时间；
4. 同时采集两端 MiLink 日志。

## 日志采集

推荐使用 HyperEars“设置 > 调试”中的“导出日志”：

1. 在 HyperEars 中打开“设置 > 调试”，开启“详细日志”；
2. 在 LSPosed 设置中关闭“禁用详细日志”；
3. 在 LSPosed 设置中开启“输出日志到守护进程”；
4. 保持“Xposed API 调用保护”开启，无需为 HyperEars 关闭；
5. 重新启动需要观察的蓝牙、MiLink 或厂商控制 App 进程，并复现问题；
6. 返回 HyperEars，打开“设置 > 调试”，选择“导出日志”，在系统文件选择器中保存文本文件。

导出的文件包含两部分：

- 蓝牙、MiLink 和已勾选厂商 App 注入进程通过 libxposed 写入的 HyperEars 模块日志；
- HyperEars 应用自身此前记录的设置变更与 Root 快捷控制结果。

注入日志由各目标进程产生，但统一写入 LSPosed 守护进程日志，不会写入目标应用的数据目录。
导出文件头同时记录生成时间、HyperEars 版本、设备型号、Android 版本和日志开关状态。
LSPosed 日志部分最多保留最新 2 MiB；应用日志保存在 HyperEars 私有目录，使用当前与上一份
滚动文件，每份上限 256 KiB。日志开关关闭时，两条链路都不会新增诊断记录。应用内导出需要
Root，用于读取并过滤 `/data/adb/lspd/log` 中的 HyperEars 模块条目；没有 Root 时可在
LSPosed 管理器中手动导出模块日志。

如需额外采集 Android 崩溃，可使用：

```powershell
adb logcat -v threadtime AndroidRuntime:E '*:S'
```

若 PowerShell 对 `*` 展开有影响，可以使用：

```powershell
adb logcat -v threadtime | Select-String 'AndroidRuntime|FATAL EXCEPTION'
```

提交前删除：

- 完整蓝牙 MAC 地址，只保留 OUI 或末两组；
- WLAN 地址、ADB 地址和设备配对码；
- 账号、手机号、通知正文和其他无关个人信息；
- 与问题无关的应用日志。

## Issue 最小信息

- HyperEars 版本与 APK 来源；
- 手机/平板型号、Android 和 HyperOS 完整版本；
- LSPosed 与 MiLink 版本；
- 耳机零售名称；
- 涉及厂商控制 App 时，提供应用显示名和 Android 包名；
- 最短复现步骤、预期结果、实际结果；
- 已脱敏日志和截图。
