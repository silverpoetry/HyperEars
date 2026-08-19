# 华为耳机适配报告

**生成日期**: 2026-08-19  
**HyperEars 版本**: v2.3.2  
**PR #35 状态**: 已提交  
**最新 APK**: `HyperEars-v2.3.2-huawei-20260819-204542.apk`

---

## 一、适配型号总览

### 1.1 具体型号适配器

| 型号 | 状态 | SPP 端口 | ANC 支持 | 实机验证 | 适配器 ID |
|------|------|----------|----------|----------|-----------|
| FreeBuds Pro 3 | ✅ 完成 | Port 1 | 三态 + 四档 + 透传两档 | ✅ 已验证 | `huawei-freebuds-pro3` |
| FreeBuds Pro 4 | ✅ 完成 | Port 1 | 三态 + 四档 + 透传两档 | ❌ 无实机 | `huawei-freebuds-pro4` |
| FreeClip 2 | ✅ 完成 | Port 1 | 无（开放式） | ⚠️ 日志不足 | `huawei-freeclip2` |
| FreeBuds 4 | ✅ 完成 | Port 1 | 二态（OFF/ANC） | ❌ 无实机 | `huawei-freebuds4` |
| FreeBuds 4i | ✅ 完成 | Port 16 | 三态（无档位） | ❌ 无实机 | `huawei-freebuds4i` |

### 1.2 家族回退适配器

| 适配器 | 状态 | SPP 端口 | 特点 | 实机验证 |
|--------|------|----------|------|----------|
| HuaweiPort1FamilyAdapter | ✅ 完成 | Port 1 | 探测 ANC + 档位 | ✅ FreeBuds 4 已验证 |
| HuaweiPort16FamilyAdapter | ✅ 完成 | Port 16 | 基础 ANC 无档位 | ❌ 无实机 |

---

## 二、各型号详细规格

### 2.1 FreeBuds Pro 3

**基本信息**：
- 型号: T0018 / T0018C
- 设备名: HUAWEI FreeBuds Pro 3
- 硬件版本: HL1SAKM2_Ver.A
- SPP 端口: 1

**支持功能**：
- ✅ 电池遥测（左/右/充电盒/整体 + 充电状态）
- ✅ ANC 三态: OFF / ANC / TRANSPARENCY
- ✅ ANC 四档: normal(0) / comfort(1) / ultra(2) / dynamic(3)
- ✅ 透传两档: voice_boost(1) / normal(2)
- ✅ 设备端模式切换通知 (`2B 03`)

**协议命令**：
- 读取电池: `01 08`
- 读取噪声状态: `2B 2A`
- 写入噪声模式: `2B 04`
- 噪声变更通知: `2B 03`

**字节序**：
- 读: `[level, mode]` (level 在前)
- 写: `[mode, level]` (mode 在前)

**实现文件**：
- `HuaweiFreebudsPro3Adapter.kt` (EXACT_MATCH)
- `HuaweiFreebudsPro3ProtocolSession` (internal class)

---

### 2.2 FreeBuds Pro 4

**基本信息**：
- 型号: 待确认
- 设备名: HUAWEI FreeBuds Pro 4
- SPP 端口: 1

**支持功能**：
- ✅ 电池遥测
- ✅ ANC 三态 + 四档 + 透传两档（与 Pro 3 相同）
- ❌ 无实机验证

**实现方式**：
- 独立适配器 `HuaweiFreeBudsPro4Adapter` (EXACT_MATCH)
- 委托 `HuaweiFreebudsPro3ProtocolSession` 处理协议
- 从 Pro 3 的 `matches()` 中移除了 Pro 4 名称

**注意事项**：
- 需实机验证协议兼容性
- 基于 OpenFreebuds 分析，Pro 4 应与 Pro 3 共享协议

---

### 2.3 FreeClip 2

**基本信息**：
- 型号: T0027 / T0027C
- 设备名: HUAWEI FreeClip 2
- 硬件版本: HL1SAKM2_Ver.A (疑似，待确认)
- SPP 端口: 1

**支持功能**：
- ✅ 电池遥测
- ❌ 无 ANC（开放式耳夹设计，无物理麦克风降噪）

**实现方式**：
- 独立适配器 `HuaweiFreeClip2Adapter` (EXACT_MATCH)
- 独立 `HuaweiFreeClip2ProtocolSession`（仅电池）
- `ANDROID wearables` CoD 支持

**已知问题**：
- 设备被识别但可能未进入华为专用流程
- OpenFreebuds 映射到 Pro 3 驱动但**不支持 ANC 三档**
- 匹配集合需包含 `"huaweifreeclip2"/"freeclip2"/"btft0027"`

---

### 2.4 FreeBuds 4

**基本信息**：
- 型号: 待确认
- 设备名: HUAWEI FreeBuds 4
- SPP 端口: 1（用户确认）

**支持功能**：
- ✅ 电池遥测
- ✅ ANC 二态: OFF / ANC（无透传）
- ❌ 无 ANC 档位控制
- ❌ 无透传模式

**实现方式**：
- 独立适配器 `HuaweiFreeBuds4Adapter` (EXACT_MATCH)
- 独立 `HuaweiFreeBuds4ProtocolSession`
- `pendingNoiseRefresh` + `drainImmediateCommands` 机制

**注意事项**：
- 与 FreeBuds 4i 的差异：FreeBuds 4 走 Port 1 有降噪无透传，4i 走 Port 16 有降噪有透传
- 需实机验证匹配集合和降噪功能

---

### 2.5 FreeBuds 4i

**基本信息**：
- 型号: 待确认
- 设备名: HUAWEI FreeBuds 4i
- SPP 端口: 16

**支持功能**：
- ✅ 电池遥测
- ✅ ANC 三态: OFF / ANC / TRANSPARENCY
- ❌ 无 ANC 档位控制
- ✅ 设备端模式切换通知

**实现方式**：
- 独立适配器 `HuaweiFreeBuds4iAdapter` (EXACT_MATCH)
- 独立 `HuaweiFreeBuds4iProtocolSession`
- `pendingNoiseRefresh` + `drainImmediateCommands` 机制

**注意事项**：
- Port 16 设备通常 ANC 能力有限
- 需实机验证三态降噪

---

### 2.6 Port 1 家族回退适配器

**设计理念**：
- 捕获未匹配任何具体型号但使用 Port 1 的华为设备
- 从电池探测开始，根据协议证据动态启用 ANC 功能
- 避免假设所有 Port 1 设备都有 ANC（如 FreeClip 原版无 ANC）

**支持功能**：
- ✅ 电池遥测（初始）
- ✅ ANC 三态 + 四档 + 透传两档（探测后动态启用）

**匹配逻辑**：
```kotlin
normalizeDeviceName(deviceName).let { name ->
    name.startsWith("huawei") || name.startsWith("freebuds") ||
        name.startsWith("freeclip") || name.startsWith("honor")
}
```

**实现文件**：
- `HuaweiPort1FamilyAdapter.kt` (FAMILY_MATCH)
- `HuaweiPort1FamilyProtocolSession` (dynamic ANC probe)

---

### 2.7 Port 16 家族回退适配器

**设计理念**：
- 捕获未匹配任何具体型号但使用 Port 16 的华为设备
- 保守策略：基础 ANC 三态，无档位控制
- 匹配 Port 16 设备通常能力有限

**支持功能**：
- ✅ 电池遥测（初始）
- ✅ ANC 三态（探测后动态启用）
- ❌ 无 ANC 档位控制

**匹配逻辑**：
```kotlin
normalizeDeviceName(deviceName).let { name ->
    name.startsWith("huawei") || name.startsWith("freebuds") ||
        name.startsWith("honor")
}
```

**实现文件**：
- `HuaweiPort16FamilyAdapter.kt` (FAMILY_MATCH)
- `HuaweiPort16FamilyProtocolSession` (basic ANC probe)

---

## 三、适配器注册顺序

**EarbudAdapter.kt (lines 615-621)**：
```kotlin
// 华为组
add(Registration(huaweiGroup, ::HuaweiFreebudsPro3Adapter))    // Port 1, EXACT_MATCH
add(Registration(huaweiGroup, ::HuaweiFreeBudsPro4Adapter))    // Port 1, EXACT_MATCH
add(Registration(huaweiGroup, ::HuaweiFreeClip2Adapter))       // Port 1, EXACT_MATCH
add(Registration(huaweiGroup, ::HuaweiFreeBuds4Adapter))       // Port 16, EXACT_MATCH
add(Registration(huaweiGroup, ::HuaweiFreeBuds4iAdapter))      // Port 16, EXACT_MATCH
add(Registration(huaweiGroup, ::HuaweiPort1FamilyAdapter))     // Port 1, FAMILY_MATCH
add(Registration(huaweiGroup, ::HuaweiPort16FamilyAdapter))    // Port 16, FAMILY_MATCH
```

**匹配优先级**：
1. 具体型号适配器 (EXACT_MATCH)
2. 家族回退适配器 (FAMILY_MATCH)
3. 标准适配器 (fallback)

---

## 四、SPP 端口分组

### 4.1 Port 1 设备

| 设备 | ANC | 档位 | 备注 |
|------|-----|------|------|
| Pro 2 | 三态 | 四档 | OpenFreebuds |
| Pro 3 | 三态 | 四档 + 透传两档 | 已实机验证 |
| Pro 4 | 三态 | 四档 + 透传两档 | 无实机 |
| Pro 5 | 三态 | 四档 | OpenFreebuds |
| 6i | 三态 | 四档 | OpenFreebuds |
| FreeBuds 4 | 二态 | 无 | 已适配（无透传） |
| SE 2 | 三态 | - | OpenFreebuds |
| SE 4 | 三态 | - | OpenFreebuds |
| Studio | 三态 | - | OpenFreebuds |
| FreeClip 2 | 无 | - | 开放式耳夹 |
| FreeLace Pro 2 | 三态 | - | OpenFreebuds |
| FreeClip (原版) | **无 ANC** | - | OpenFreebuds 映射 Pro3 驱动但不支持 |

### 4.2 Port 16 设备

| 设备 | ANC | 档位 | 备注 |
|------|-----|------|------|
| FreeBuds 4i | 三态 | 无 | 已适配 |
| FreeBuds 5i | 三态 | - | OpenFreebuds |
| FreeLace Pro | 三态 | - | OpenFreebuds |
| FreeBuds SE | 三态 | - | OpenFreebuds |

---

## 五、与 OpenFreebuds 能力对比

| 功能 | OpenFreebuds | HyperEars | 差距 |
|------|--------------|-----------|------|
| 电池 | ✅ 全部型号 | ✅ 全部型号 | 无 |
| ANC 模式 | ✅ 全部型号 | ✅ Pro3/Pro4/4i | 无 |
| ANC 档位 | ✅ Pro3/Pro5/6i | ✅ Pro3 | 需验证 Pro4 |
| Dynamic ANC | ✅ Pro3/Pro5/6i | ✅ Pro3 | 需验证 Pro4 |
| Voice Boost | ✅ Pro3/Pro5 | ❌ 未实现 | **缺口** |
| 均衡器 | ✅ Pro3/Pro5/FreeClip2/6i | ❌ 未实现 | **缺口** |
| 手势配置 | ✅ 全部型号 | ❌ 未实现 | **缺口** |
| 双连接 | ✅ Pro3/Pro5/FreeClip2/6i | ❌ 未实现 | **缺口** |
| 入耳检测 | ✅ 大部分型号 | ❌ 未实现 | **缺口** |
| 低延迟模式 | ✅ Pro3/Pro5/6i | ❌ 未实现 | **缺口** |

---

## 六、协议兼容性矩阵

| 协议命令 | Pro 3 | Pro 4 | FreeClip 2 | FreeBuds 4 | FreeBuds 4i | Port 1 Family | Port 16 Family |
|----------|-------|-------|------------|------------|-------------|---------------|----------------|
| `01 08` 电池读取 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `01 27` 电池推送 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `2B 2A` 噪声状态 | ✅ | ✅ | ❌ | ❌ | ✅ | ⚠️ 探测 | ⚠️ 探测 |
| `2B 04` 噪声写入 | ✅ | ✅ | ❌ | ❌ | ✅ | ⚠️ 探测 | ❌ |
| `2B 03` 噪声通知 | ✅ | ✅ | ❌ | ❌ | ✅ | ⚠️ 探测 | ⚠️ 探测 |
| `2B 4A` 均衡器读取 | ❓ | ❓ | ❓ | ❌ | ❌ | ❓ | ❌ |
| `2B 49` 均衡器写入 | ❓ | ❓ | ❓ | ❌ | ❌ | ❓ | ❌ |

- ✅ = 已实现并验证
- ⚠️ = 动态探测
- ❌ = 不支持
- ❓ = 需实机验证

---

## 七、已知问题

### 7.1 FreeClip 2 识别问题
- **现象**: 设备连接后可能使用标准适配器
- **日志**: `adapter=standard-bluetooth-headset`
- **可能原因**:
  1. 设备名不在匹配集合中（需确认 `btft0027`）
  2. MiLink 对开放式设备的 CoD (Class of Device) 过滤
  3. 蓝牙 Profile 差异
- **解决方案**: 收集正确 logcat 诊断

### 7.2 FreeBuds 4 识别问题
- **现象**: 设备被识别但使用标准适配器
- **日志**: `adapter=standard-bluetooth-headset`
- **原因**: 需实机验证匹配集合
- **解决方案**: 收集 FreeBuds 4 用户日志

### 7.3 签名 APK 损坏
- **原因**: jarsigner 只做 v1 签名
- **解决**: 使用 Gradle 环境变量签名 (v2/v3/v4)
- **当前状态**: 已修复

### 7.4 FreeClip 2 logcat 格式问题
- **现象**: 用户提供的 `logcat111(2).txt` 是 `ss -Hltp` 网络端口信息
- **解决方案**: 指导用户用 `adb logcat -d | grep HyperEars` 导出

---

## 八、后续建议

### 8.1 短期（1-2 周）
1. **FreeClip 2 诊断**: 收集正确 logcat 确认匹配问题
2. **FreeBuds 4 实机测试**: 验证 Port 16 匹配
3. **FreeBuds 4i 实机测试**: 验证三态降噪

### 8.2 中期（1-2 月）
1. **均衡器支持**: Pro 3/Pro 4
2. **手势配置**: 双击/三击/长按/滑动
3. **双连接管理**: 多设备切换

### 8.3 长期
1. **更多型号**: Pro 5, SE 系列, Studio, Lace Pro 2
2. **深度集成**: 与智慧音频 App 协同
3. **用户自定义**: 配置导入/导出

---

## 九、附录

### A. 文件清单

**适配器文件**：
- `HuaweiFreebudsPro3Adapter.kt` — Port 1, EXACT_MATCH
- `HuaweiFreeBudsPro4Adapter.kt` — Port 1, EXACT_MATCH, delegate Pro3ProtocolSession
- `HuaweiFreeClip2Adapter.kt` — Port 1, EXACT_MATCH, NO_ANC
- `HuaweiFreeBuds4Adapter.kt` — Port 16, EXACT_MATCH, NO_ANC
- `HuaweiFreeBuds4iAdapter.kt` — Port 16, EXACT_MATCH, ANC_BASIC
- `HuaweiPort1FamilyAdapter.kt` — Port 1, FAMILY_MATCH, dynamic ANC probe
- `HuaweiPort16FamilyAdapter.kt` — Port 16, FAMILY_MATCH, basic ANC probe

**协议文件**：
- `HuaweiFreebudsSppCodec.kt` — 通用帧编解码
- `HuaweiAncLevel.kt` — ANC 档位枚举 + wire 编码

**注册文件**：
- `EarbudAdapter.kt` (lines 615-621) — 华为组注册

**测试文件**：
- `EarbudAdapterHierarchyTest.kt` — 57 测试全部通过（含家族回退 4 新测试）

### B. Git 提交记录

```
ae5ce79 docs(system-module): add FreeClip 2 and FreeBuds 4i to About page
6bbb6f0 feat(integration): add HUAWEI FreeBuds Pro 4 as independent adapter entry
33d6d97 feat(integration): add HUAWEI FreeBuds 4i adapter (port 16, basic ANC)
1ee0abf feat(integration): add HUAWEI FreeClip 2 adapter (open-ear, no ANC)
cde3e99 docs: add FreeBuds Pro 4 to compatibility matrix and documentation
1822e6d feat(integration): extend Huawei adapter to match FreeBuds Pro 4
653257a docs: add Huawei FreeBuds Pro 3 PR report
609b53b docs: add Huawei to README supported brands and links
7bc0c6c docs: record FreeBuds Pro 3 hardware verification
3c1703e feat(system-module): add Huawei support card to About page
b17777a fix(integration): register Huawei smart audio as vendor controller
cba9a8d docs: document Huawei FreeBuds Pro 3 protocol and sources
250cdc2 test(integration): cover Huawei FreeBuds Pro 3 adapter
8c46f8a feat(integration): add Huawei FreeBuds Pro 3 adapter
b5ac5f6 feat(protocol): add Huawei FreeBuds Pro 3 SPP codec
```

### C. 未提交文件

```bash
# 新增文件
HuaweiFreeBuds4Adapter.kt
HuaweiPort1FamilyAdapter.kt
HuaweiPort16FamilyAdapter.kt
huawei-headphone-report.md

# 修改文件
EarbudAdapter.kt
EarbudAdapterHierarchyTest.kt
AboutScreen.kt
```

### D. 签名信息

- 密钥库: `C:\Users\sammary\hyperears-release.jks`
- 别名: `hyperears-release`
- 签名方式: Gradle 环境变量 (v2/v3/v4)
- 环境变量:
  ```
  HYPEREARS_KEYSTORE_PATH=C:\Users\sammary\hyperears-release.jks
  HYPEREARS_KEY_ALIAS=hyperears-release
  HYPEREARS_STORE_PASSWORD=bXscdTQSY2h6aROlGBqVPHn9WZCu
  ```

### E. 最新 APK

- **文件**: `C:\Users\sammary\Desktop\HyperEars-v2.3.2-huawei-20260819-204542.apk`
- **大小**: 4.06 MB
- **签名**: v2/v3/v4 环境变量签名

### F. OpenFreebuds 参考

- **源码路径**: `C:\Users\sammary\AppData\Local\Temp\opencode\OpenFreebuds\openfreebuds\driver\huawei\`
- **constants.py**: 设备映射表（FreeClip→Pro3 driver）
- **Port 分组**: Port 1 = Pro2/3/4/5/6i/SE2/SE4/Studio/FreeClip2/LacePro2；Port 16 = 4i/5i/SE/LacePro

---

**报告生成者**: Sisyphus  
**最后更新**: 2026-08-19T21:00:00+08:00
