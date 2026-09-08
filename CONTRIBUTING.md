# Contributing to HyperEars

感谢参与兼容性研究。HyperEars 运行在系统蓝牙和 MiLink 进程中，因此正确性、证据和
最小注入范围优先于“尽量显示更多功能”。

## 开发环境

- JDK 17；
- Android SDK 37；
- Git；
- 用于实机验证的 Android 15+ HyperOS 设备和 LSPosed API 101+。

验证命令：

```powershell
.\gradlew.bat --no-daemon testDebugUnitTest `
  :protocol-test:assembleDebug `
  :system-module:lintRelease `
  :system-module:assembleRelease
```

文档验证：

```powershell
npx --yes markdownlint-cli2@0.18.1 "*.md" "docs/**/*.md" ".github/**/*.md"
node tools/validate-docs.mjs
```

## 设计规则

1. 设备匹配按“具体型号 → 厂商家族 → 标准耳机”从窄到宽执行。
2. Adapter 声明候选身份、端点、型号差异配置和会话内已确认能力；ProtocolSession 只处理
   一个设备会话的帧状态。
3. WireCodec 不创建连接、不访问 Android Context、不修改 UI。
4. 名称、服务 UUID、设备形态和具体型号配置只允许选择候选协议。初始匹配 Adapter 不得
   静态开放私有电量源、噪声模式或专用卡片；对应合法状态响应到达后才允许逐项开放。
5. 原生系统耳机必须留在官方路径。
6. 优先 Hook 稳定的语义边界；避免按混淆方法名、视图层级或定时轮询打补丁。
7. 新增后台工作必须说明生命周期、退避、并发和耗电影响。
8. 控制操作必须使用 `ControlRequest`。新增厂商或型号请求在 `integration` 的 sealed
   请求层级中声明为 `@Serializable` 子类型，并使用带命名空间的稳定 `@SerialName`。
9. 禁止 CardAdapter 手写 Intent extra、JSON、`Bundle`、`Map<String, Any>` 或直接写蓝牙
   通道；统一调用 `environment.controlSender(address, request)`。
10. Adapter 通过继承的 `controlRequestContract` 声明请求支持范围；ProtocolSession 只
    处理已通过当前会话校验的请求，并负责字节编码。
11. 动态设备状态必须使用 `DeviceFeatureState` 子类型和 Adapter 的 `featureStateContract`；
    不得向 `EarbudState`、`AdapterRuntimeState`、Intent extra 或公共枚举增加型号专属字段。
    协议身份、能力证据和连接生命周期保持为独立模型。
12. 协议状态观测默认由 Adapter 立即接受。若实测证明某型号存在连接初期暂态值，Adapter
    必须通过 `FeatureReportDecision.HOLD` 暂缓提交，并在事件作用域中声明有界的
    `requestState(featureId, delayMs)`；达到接受条件时声明 `cancelStateRequest(featureId)`。
    Android 会话只机械执行有序 `AdapterEffect`，不得保存型号目标、重试次数或接受策略；
    WireCodec、ProtocolSession、MiLink Hook 和 UI 中不得按型号休眠或建立常驻轮询。

新增或变更厂商控制 App 时，还必须同步更新 `ControlAppCatalog`、对应 Adapter 的
`controlApps`、`META-INF/xposed/scope.list` 和
[控制 App 目录](docs/control-apps.md)，并补充控制权优先顺序测试。控制 App 包名只能作为
导航目标和控制权边界，不能作为耳机品牌或型号的识别证据。

## 文档规则

1. `README.md` 和 `README_EN.md` 只提供项目概览，不复制完整型号矩阵。
2. `docs/compatibility.md` 必须按代码实际开放的电量、噪声模式和确认条件描述能力。
3. 协议文档必须区分实机验证、公开实现、参考协议和家族外推，不把候选协议写成已验证支持。
4. 控制 App 显示名、包名和声明顺序以 `ControlAppCatalog` 与 `docs/control-apps.md` 为准。
5. 外部协议来源和适用许可同步维护在 `THIRD_PARTY_NOTICES.md`。
6. 用户可见设置名称、默认值和排障步骤必须与当前 Release 界面一致。
7. 行为变化同步更新 `CHANGELOG.md`；文档不得使用控制 App 安装状态作为耳机判型依据。

## 新型号提交材料

- 零售名称及规范化别名；
- 设备形态（TWS/头戴）和 Android Profile；
- 厂商 UUID、RFCOMM channel 或 GATT service；
- 厂商控制 App 的显示名和 Android 包名（如适用），以及是否验证页面跳转和运行时退避；
- 只读查询、响应和字段解释；
- 每个控制命令的写入帧、设备回读和失败行为；
- 每项新增动态状态的读取证据、稳定 `@SerialName`、状态替换语义和卡片投影条件；
- 至少一个解析器单元测试和一个 Adapter 选择测试；
- 对应 `docs/*-protocol.md` 更新。

请将完整 MAC 替换成合成地址，或只保留公开 OUI。例如
`BC:87:FA:00:00:01` 可以表达厂商 OUI，而不公开个人设备标识。

## 代码与提交

### 维护分支与实机复核

设备适配 PR 需要维护者整理时，保留原作者提交，在独立维护分支中修订并提供签名验证包。
改变了协议、生命周期或卡片行为的维护版须获得针对最终维护提交的实机复核，才可合入主线并
关闭原 PR。等待期间维护 PR 保持 Draft，原 PR 保持开放，不提前添加自动关闭原 PR 的关键字。
原版实测、单元测试、CI 和签名构建分别记录，不能代替维护版实机复核。

维护记录：2026-09-07，#72 在共享卡片布局尚未得到实机复核时提前合入，并自动关闭 #62。
2026-09-08 维护者决定保留这次合入，不回退代码和 PR 状态；该决定仅适用于本次事件，
后续仍须执行上述复核流程。FitBuds Turbo 的维护版布局继续标记为待复核。

### 提交要求

- Kotlin/Java 使用 4 空格，Markdown/YAML 使用 2 空格，统一 LF；
- 公共协议常量说明来源和语义，不只记录十六进制值；
- 不提交 APK、密钥、抓包原文件、反编译 APK 或厂商版权图片；
- 提交信息使用简短祈使句；
- PR 说明影响范围、证据、测试和实机结果。

## 许可

提交代码即表示你有权按 GNU GPL-3.0-only 提供该贡献。引用外部协议资料时必须在
`THIRD_PARTY_NOTICES.md` 或相关协议文档中标明来源和适用许可。
