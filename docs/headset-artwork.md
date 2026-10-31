# 耳机展示资源

从 3.0.0 起，原生提示为所有注册 Adapter 提供通用资源。资源声明属于 Adapter 的静态展示信息，
不参与品牌识别、协议确认、系统归属或私有能力开放。

## 默认资源与继承

| Adapter 形态 | 耳机资源 | 盒资源 |
| --- | --- | --- |
| `TWS` | `headset_generic_earbuds` | `headset_generic_case` |
| `HEADPHONES` | `headset_generic_headphones` | 无 |

`EarbudAdapter.artwork` 按 `formFactor` 返回默认值，具体型号可以继承家族声明，也可以单独覆盖。
NC20 Pro 覆盖耳机与充电盒资源，其余既有型号沿用上述默认映射；`HeadsetArtworkTest` 遍历完整
`EarbudAdapterRegistry.adapters` 验证覆盖。型号目录仍以[兼容性表](compatibility.md)为准；
不在 UI 后端复制品牌／型号列表。

声明进入 `AdapterSnapshot`，随现有设备状态跨进程传输。厂商 App 退避后的标准投影保留
展示资源。图片变化不能使未确认的降噪、电量或佩戴能力变为可用。

## 新型号专用图片

1. 将有权使用的透明 PNG 放入 `system-module/src/main/res/drawable-nodpi/`，
   文件名使用 `headset_<品牌>_<型号>_<部件>.png`。
2. 在对应 Adapter 覆盖声明，保持协议层不依赖 Android `Drawable`、Context 或 `R`：

   ```kotlin
   override val artwork = HeadsetArtwork(
       headset = HeadsetArtworkId("headset_example_model_earbuds"),
       chargingCase = HeadsetArtworkId("headset_example_model_case"),
   )
   ```

3. 家族默认资源可由具体型号继承。若只修改一个部件，用父级 `artwork.copy(...)`。
4. 同步记录图片来源、授权、作者及必要署名；第三方协议代码的许可不会自动覆盖产品图片。
5. 验证浅色／深色背景、TWS／整机布局、缺少盒电量，以及混淆和资源压缩后的包。

资源 ID 只允许 `headset_` 前缀和小写字母、数字、下划线。禁止文件路径、URL 和外部包资源。
加载失败时逐项回退到当前设备形态的默认图，不影响协议会话。

## Android 加载边界

`NativeArtworkLoader` 只从 HyperEars 已安装包加载资源，按需采样到最长边不超过 640 px，
使用有内存上限的缓存，并为每个 View 创建独立 Drawable，避免共享 bounds 或状态。
接受的源图尺寸为 1–4096 px。`headset_artwork_keep.xml` 保留通过名字解析的资源，
因此贡献者不需要另外添加 R8 名称映射或资源注册表。

AirPods 原生 Controller 的资源初始化入口使用这些图片。该窗口跳过 Apple 云图片下载，
继续使用原生布局、电量图标、文字、动画和关闭逻辑；不扫描 View 树，不替换系统资源文件，
不把资源名写成设备型号 ID。系统连接提示的动画和通知小图标继续使用宿主原有资源。

本模块窗口在原生电量绘制完成后，可按已知盒电量显示既有的充电盒区域。
这项扩展只改变经过资源与类型校验的盒区域可见性，不修改充电状态、原生文字或图标，
也不影响真实 Apple 窗口。未知盒电量或不兼容的原生布局保留相应的隐藏／原生处理。

## NC20 Pro 展示资源

新增型号插图根据用户提供的 NC20 Pro 产品参考图经生成模型转换；保留产品形态与标识，
参考原图不随项目分发。下述无参考图、无品牌的说明只适用于原有三个通用耳机资源。

## 通用资源来源

三个通用 PNG 于 2026-10-26 使用内置 imagegen 生成，作为本项目通用展示素材，
没有使用厂商图片作为输入，随本项目许可证分发。

共同提示词：透明背景、无品牌无文字、居中完整展示、哑光珍珠灰材质、柔和银色高光、
适用于深浅背景的小尺寸原生耳机电量窗口；简洁的三维工业产品插图，不复制具体商业产品。
三个主体分别为：一对短柄入耳式耳机、一副椭圆耳罩头戴耳机、一个闭合的横向圆角充电盒。

原图保留完整透明通道，项目内资源没有使用厂商商标或产品标识。
逐图原始提示词与生成方式保存在[资源生成记录](headset-artwork-prompts.json)。
