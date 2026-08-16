# 厂商控制 App 与 LSPosed 作用域

## 1. 用途与边界

HyperEars 将厂商控制 App 用于两个彼此独立的功能：

1. MiLink 卡片的“更多设置”选择“厂商 App”时，可以打开当前 Adapter 声明的控制 App；
2. 启用“运行时退避”后，已 Hook 的控制 App 进程存活时，HyperEars 让出耳机私有控制通道。

控制 App 的包名不是耳机型号或品牌的识别证据。耳机仍由 Android 蓝牙身份、设备名称、
专属服务和合法协议响应选择 Adapter；安装或运行表中的 App 不会把其他耳机改判为相应品牌。

应用商店名称、桌面名称和地区翻译可能随版本变化。LSPosed 作用域以及 HyperEars 的运行时
仲裁均以 Android 包名为准。下表名称采用当前代码目录中的显示名；例如 HeyMelody 可能显示为
“欢律”，`com.bose.bosemusic` 的旧版本可能显示为 Bose Music，Sony 应用的旧名称为
Sony | Headphones Connect。

## 2. 当前目录

| 耳机适配范围 | 控制 App | Android 包名 | Adapter 声明顺序 |
|---|---|---|---:|
| vivo / iQOO TWS | vEarphones | `com.vivo.vivotws` | 1 |
| OPPO Enco | HeyMelody | `com.heytap.headset` | 1 |
| OPPO Enco | Wireless Earphones | `com.oplus.melody` | 2 |
| OPPO Enco | Wireless Earphones (ColorOS 11) | `com.coloros.oppopods` | 3 |
| Bose | Bose | `com.bose.bosemusic` | 1 |
| Bose | Bose Connect | `com.bose.monet` | 2 |
| Edifier / 漫步者 | Edifier Connect | `com.edifier.edifierconnect` | 1 |
| ROSESELSA / 弱水时砂 | ROSELINK | `cn.ikaile.ruoshui.client` | 1 |
| StarRing / 籁特易耳 | LightYear | `cn.lightyeartech.android` | 1 |
| NiceHCK / YuanDao | 原道 | `com.yuandao.nicehck` | 1 |
| QCY | QCY | `com.qcymall.googleearphonesetup` | 1 |
| Sony | Sony \| Sound Connect | `com.sony.songpal.mdr` | 1 |
| 华为 FreeBuds Pro 3 | 智慧音频 | `com.huawei.smartaudio` | 1 |

“Adapter 声明顺序”只在同一家族声明了多个控制 App 时使用：

- 打开“更多设置”时，当前已经接管控制权的 App 优先；否则按表中顺序选择第一个已安装且
  具有 Launcher Activity 的 App；
- 多个已 Hook 的候选 App 同时运行时，按表中顺序确定当前控制权所有者；
- 不需要安装同一品牌的全部 App，只需选择设备上实际安装并可能使用的版本。若确实同时
  使用多个候选 App，应为这些已安装 App 分别启用作用域。

StarRing、ROSESELSA 等名称描述的是耳机 Adapter 的适用范围；控制 App 的桌面品牌名称可以
不同。标准耳机回退 Adapter 不声明控制 App，任何厂商 App 都不会因此接管标准回退会话。

## 3. 两项功能的条件

### 3.1 点击卡片“更多设置”

设置页直接显示当前打开动作，并可从下拉列表选择三个目标：

- **系统设置**：打开当前真实蓝牙设备详情；设备详情不可用时回退到蓝牙设置列表；
- **厂商 App**：按以下顺序选择控制 App；没有可启动候选时回退到系统设置；
- **HyperEars**：打开 HyperEars 主页；启动失败时回退到系统设置。

选择“厂商 App”后，MiLink 的“更多设置”按以下顺序处理：

1. 读取当前耳机会话的 Adapter 快照；
2. 取得该 Adapter 声明的控制 App 列表；
3. 选择第一个已安装且可启动的 App；
4. 没有可启动候选时，打开真实蓝牙设备详情；设备详情不可用时再回退到蓝牙设置列表。

仅执行页面跳转时，技术条件是 App 已安装且具有 Launcher Activity；LSPosed 作用域不参与
Android 的 `startActivity`。但是，未勾选作用域的 App 无法向 HyperEars 登记进程存活状态，
因此不能参与运行时退避。需要完整的“打开控制 App + 自动让出私有通道”行为时，应为该 App
启用作用域。

### 3.2 运行时退避

“运行时退避”默认关闭。进入退避必须同时满足：

- 当前耳机 Adapter 声明了该控制 App；
- HyperEars 设置中的“运行时退避”已开启；
- LSPosed 已为该 App 包名启用 HyperEars 作用域；
- App 的至少一个已 Hook 进程正在运行并完成 Binder 登记。

控制 App 进程中只 Hook `Application.attach(Context)`，用于登记进程级 Binder 令牌。
HyperEars 不读取控制 App 的界面、账号、私有文件、蓝牙对象或协议实现，也不以 App 是否建立
GATT、RFCOMM 或 L2CAP 连接作为退避条件。

进入退避后，HyperEars 关闭自己建立的耳机私有通道并移除私有电量、噪声控制和型号专属卡片
扩展；MiLink 流转、系统音量、设备形态和 Android 标准电量继续保留。该 App 的所有已 Hook
进程退出后，Bluetooth 进程通过 Binder death 恢复模块控制；耳机仍连接时重新建立所需私有
通道，耳机已断开时不额外连接。

从最近任务划走或按返回键不保证 App 进程退出。需要立即恢复 HyperEars 控制时，应手动强制
停止控制 App，或使用 HyperEars 设置页中需要 Root 的“停止厂商应用”。HyperEars 不会自动
结束厂商 App。

## 4. 状态矩阵

| 控制 App 状态 | “更多设置”选择“厂商 App” | 开启“运行时退避”后的结果 |
|---|---|---|
| 未安装 | 回退到真实蓝牙设备详情 | 不退避 |
| 已安装、未勾选作用域 | 可以打开 App | 无进程登记，不退避 |
| 已勾选作用域、进程未运行 | 可以打开 App | 模块继续持有私有通道 |
| 已勾选作用域、进程运行、退避关闭 | 可以打开 App | 模块不主动让出，可能与厂商 App 争用私有协议 |
| 已勾选作用域、进程运行、退避开启 | 可以打开 App | 模块让出私有通道，卡片保留标准集成 |
| 已退避，App 所有进程退出 | 下次仍可打开 App | 模块恢复私有通道和协议确认 |

## 5. LSPosed 配置

必选核心作用域始终只有：

```text
com.android.bluetooth
com.milink.service
```

厂商控制 App 是可选作用域。推荐配置顺序：

1. 先启用两个核心作用域；
2. 只从本文目录中选择设备上实际安装、且确实会用于控制当前耳机的 App；
3. 修改作用域后重启对应控制 App 进程；首次安装或模块升级后建议重启设备；
4. 在 HyperEars 中按需把“更多设置”设为“厂商 App”，并配置“运行时退避”。

不要勾选 Settings、System UI 或所有应用。扩大作用域不会增加耳机协议兼容性，只会扩大注入面。
LSPosed 的“禁用详细日志”和“输出日志到守护进程”只影响诊断日志，不影响控制 App 的跳转或
退避；日志设置见[问题排查](troubleshooting.md#日志采集)。“Xposed API 调用保护”可以保持开启。

## 6. 排查

确认设备上安装的包名：

```powershell
adb shell pm path com.vivo.vivotws
```

命令返回 `package:.../base.apk` 表示该包存在；没有输出表示未安装。桌面显示名相同不代表包名
相同，不应把未登记的重打包版本或第三方客户端加入 LSPosed 作用域。

运行看板显示“专有控制 App 运行中”时，表示 Bluetooth 进程已经收到该 App 的有效 Binder
登记并进入退避，不表示厂商 App 已经连接耳机。若状态不出现或退出后不恢复，按
[问题排查](troubleshooting.md)采集 Bluetooth 进程和对应厂商 App 的模块日志。

## 7. 维护规则

控制 App 目录的代码真源为
[`ControlAppCatalog`](../integration/src/main/java/dev/hyperears/integration/ControlAppCatalog.kt)，
静态作用域位于
[`scope.list`](../system-module/src/main/resources/META-INF/xposed/scope.list)。新增或变更控制 App
必须同时完成：

1. 在 `ControlAppCatalog` 登记唯一包名和稳定显示名；
2. 由对应家族 Adapter 显式声明 `controlApps` 及优先顺序；
3. 将包名加入 `scope.list`；
4. 更新本文目录和适用范围；
5. 更新 `ControlAppArbitrationTest`，并保持 `ControlAppScopeTest` 通过。

包名只定义控制权边界，不得加入设备匹配证据。若厂商更换包名，应作为新目录项单独验证，
不能仅按相似名称或签名未知的兼容客户端放宽匹配。
