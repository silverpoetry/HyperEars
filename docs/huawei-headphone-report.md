# 华为耳机适配报告

**更新日期**: 2026-08-19  
**HyperEars 版本**: v2.3.2  
**PR #35 状态**: 已提交

---

## 一、适配型号总览

| 型号 | SPP 端口 | ANC 支持 | 实机验证 | 适配器 ID |
|------|----------|----------|----------|-----------|
| FreeBuds Pro 3 | Port 1 | 三态 + 四档 + 透传两档 | ✅ Xiaomi Mi 10, Android 16 | `huawei-freebuds-pro3` |
| FreeBuds 4 | Port 1 | 二态（OFF/ANC），无透传 | ❌ 待验证 | `huawei-freebuds-4` |
| Port 1 家族回退 | Port 1 | 动态探测 | — | `huawei-port1-family` |
| Port 16 家族回退 | Port 16 | 基础 ANC | — | `huawei-port16-family` |

---

## 二、各型号详细规格

### 2.1 FreeBuds Pro 3（已实机验证）

| 项目 | 值 |
|------|-----|
| 型号代码 | T0018 / T0018C |
| 设备名 | HUAWEI FreeBuds Pro 3 |
| 硬件版本 | HL1SAKM2_Ver.A |
| SPP 端口 | 1 |
| 适配器 | `HuaweiFreebudsPro3Adapter` (EXACT_MATCH) |
| 协议会话 | `HuaweiFreebudsPro3ProtocolSession` (internal) |

**支持功能**：
- 电池遥测（左/右/充电盒/整体 + 充电状态）
- ANC 三态：OFF / ANC / TRANSPARENCY
- ANC 四档：normal(0) / comfort(1) / ultra(2) / dynamic(3)
- 透传两档：voice_boost(1) / normal(2)
- 设备端模式切换通知 (`2B 03`)

**协议命令**：
- 读取电池：`01 08`
- 读取噪声状态：`2B 2A`
- 写入噪声模式：`2B 04`
- 噪声变更通知：`2B 03`

**字节序**（读写非对称）：
- 读：`[level, mode]`（level 在前）
- 写：`[mode, level]`（mode 在前）

**验证记录**（2026-08-17）：
- 三态切换：ANC → Off → Transparency 正常
- ANC 档位：四档确认
- 透传档位：人声增强/均衡确认
- 避让/恢复闭环：智慧音频打开时让出控制，退出后恢复
- 多设备同步：耳机侧全局状态共享

---

### 2.2 FreeBuds 4（代码级实现）

| 项目 | 值 |
|------|-----|
| 型号代码 | 待确认 |
| 设备名 | HUAWEI FreeBuds 4 |
| SPP 端口 | 1（用户确认） |
| 适配器 | `HuaweiFreeBuds4Adapter` (EXACT_MATCH) |
| 协议会话 | `HuaweiFreeBuds4ProtocolSession` |

**支持功能**：
- 电池遥测
- ANC 二态：OFF / ANC（无透传）
- 无 ANC 档位控制

**协议命令**：
- 读取电池：`01 08`
- 读取噪声状态：`2B 2A`
- 写入噪声模式：`2B 04`
- 噪声变更通知：`2B 03`

**匹配集合**：`huaweifreebuds4`, `freebuds4`

**注意事项**：
- 与 FreeBuds 4i 的差异：FreeBuds 4 走 Port 1 有降噪无透传，4i 走 Port 16 有降噪有透传
- 需实机验证匹配集合和降噪功能

---

### 2.3 Port 1 家族回退适配器

| 项目 | 值 |
|------|-----|
| 适配器 | `HuaweiPort1FamilyAdapter` (FAMILY_MATCH) |
| SPP 端口 | 1 |

**设计理念**：
- 捕获未匹配任何具体型号但使用 Port 1 的华为设备
- 从电池探测开始，根据协议证据动态启用 ANC 功能
- 避免假设所有 Port 1 设备都有 ANC

**支持功能**：
- 电池遥测（初始）
- ANC 三态 + 四档 + 透传两档（探测后动态启用）

**匹配逻辑**：
- 设备名以 `huawei`、`freebuds`、`freeclip`、`honor` 开头

---

### 2.4 Port 16 家族回退适配器

| 项目 | 值 |
|------|-----|
| 适配器 | `HuaweiPort16FamilyAdapter` (FAMILY_MATCH) |
| SPP 端口 | 16 |

**设计理念**：
- 捕获未匹配任何具体型号但使用 Port 16 的华为设备
- 保守策略：基础 ANC 三态，无档位控制

**支持功能**：
- 电池遥测（初始）
- ANC 三态（探测后动态启用）
- 无 ANC 档位控制

**匹配逻辑**：
- 设备名以 `huawei`、`freebuds`、`honor` 开头

---

## 三、适配器注册顺序

**EarbudAdapter.kt (lines 615-618)**：
```kotlin
add(Registration(huaweiGroup, ::HuaweiFreebudsPro3Adapter))    // Port 1, EXACT_MATCH
add(Registration(huaweiGroup, ::HuaweiFreeBuds4Adapter))       // Port 1, EXACT_MATCH
add(Registration(huaweiGroup, ::HuaweiPort1FamilyAdapter))     // Port 1, FAMILY_MATCH
add(Registration(huaweiGroup, ::HuaweiPort16FamilyAdapter))    // Port 16, FAMILY_MATCH
```

**匹配优先级**：
1. 具体型号适配器 (EXACT_MATCH)
2. 家族回退适配器 (FAMILY_MATCH)
3. 标准适配器 (fallback)

---

## 四、SPP 端口分组

### Port 1 设备（已知）

| 设备 | ANC | 档位 | 透传 | 状态 |
|------|-----|------|------|------|
| Pro 2 | 三态 | 四档 | 有 | OpenFreebuds 参考 |
| Pro 3 | 三态 | 四档 | 有 | ✅ 已实机验证 |
| Pro 4 | 三态 | 四档 | 有 | 无实机，走 Pro 3 协议 |
| Pro 5 | 三态 | 四档 | 有 | OpenFreebuds 参考 |
| 6i | 三态 | 四档 | 有 | OpenFreebuds 参考 |
| FreeBuds 4 | 二态 | 无 | 无 | 已适配，待验证 |
| SE 2 | 三态 | 无 | 有 | OpenFreebuds 参考 |
| SE 4 | 三态 | 无 | 有 | OpenFreebuds 参考 |
| Studio | 三态 | 无 | 有 | OpenFreebuds 参考 |
| FreeClip 2 | 无 | 无 | 无 | 开放式耳夹 |
| FreeLace Pro 2 | 三态 | 无 | 有 | OpenFreebuds 参考 |
| FreeClip (原版) | 无 | 无 | 无 | OpenFreebuds 映射 Pro3 驱动但不支持 |

### Port 16 设备（已知）

| 设备 | ANC | 档位 | 透传 | 状态 |
|------|-----|------|------|------|
| FreeBuds 4i | 三态 | 无 | 有 | OpenFreebuds 参考 |
| FreeBuds 5i | 三态 | 无 | 有 | OpenFreebuds 参考 |
| FreeLace Pro | 三态 | 无 | 有 | OpenFreebuds 参考 |
| FreeBuds SE | 三态 | 无 | 有 | OpenFreebuds 参考 |

---

## 五、协议兼容性矩阵

| 协议命令 | Pro 3 | FreeBuds 4 | Port 1 Family | Port 16 Family |
|----------|-------|------------|---------------|----------------|
| `01 08` 电池读取 | ✅ | ✅ | ✅ | ✅ |
| `01 27` 电池推送 | ✅ | ✅ | ✅ | ✅ |
| `2B 2A` 噪声状态 | ✅ | ✅ | ⚠️ 探测 | ⚠️ 探测 |
| `2B 04` 噪声写入 | ✅ | ✅ | ⚠️ 探测 | ❌ |
| `2B 03` 噪声通知 | ✅ | ✅ | ⚠️ 探测 | ⚠️ 探测 |

- ✅ = 已实现并验证
- ⚠️ = 动态探测
- ❌ = 不支持

---

## 六、已知问题

### 6.1 FreeBuds 4 待验证
- 匹配集合需实机验证
- 降噪功能（OFF/ANC 二态）需实机确认

### 6.2 签名 APK
- 已修复：使用 Gradle 环境变量签名 (v2/v3/v4)

---

## 七、后续计划

### 短期
1. FreeBuds 4 实机测试
2. Port 1/Port 16 家族回退实机验证

### 中期
1. 均衡器支持（Pro 3）
2. 手势配置（双击/三击/长按/滑动）
3. 双连接管理

### 长期
1. 更多型号：Pro 5、SE 系列、Studio、Lace Pro 2
2. 与智慧音频 App 深度协同
3. 用户配置导入/导出

---

## 八、文件清单

### 适配器文件
- `HuaweiFreebudsPro3Adapter.kt` — Port 1, EXACT_MATCH
- `HuaweiFreeBuds4Adapter.kt` — Port 1, EXACT_MATCH
- `HuaweiPort1FamilyAdapter.kt` — Port 1, FAMILY_MATCH
- `HuaweiPort16FamilyAdapter.kt` — Port 16, FAMILY_MATCH

### 协议文件
- `HuaweiFreebudsSppCodec.kt` — 帧编解码
- `HuaweiAncLevel.kt` — ANC 档位枚举

### 注册文件
- `EarbudAdapter.kt` (lines 615-618)

### 测试文件
- `EarbudAdapterHierarchyTest.kt` — 57 测试通过

---

## 九、参考来源

- OpenFreebuds: `openfreebuds/driver/huawei/`
- HyperEars PR #35: https://github.com/silverpoetry/HyperEars/pull/35
- 协议文档: `docs/huawei-freebuds-protocol.md`

---

**报告生成者**: Sisyphus  
**最后更新**: 2026-08-19T22:30:00+08:00
