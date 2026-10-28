# 已关闭但未直接合并的设备 PR 审查

审查日期：2026-10-28。范围 #62、#61、#55、#54、#53、#50、#39、#35、#34、#33，共10项。逐项实时读取 PR body/comments/reviews/files/commits；关联维护 PR 也实时读取。重点读取 #50 完整 diff、关闭 timeline、inline reviews（空）。不修改源码、设备、PR 评论或状态。

## 总体结论

**9项是由已合并维护版本接续，不是拒绝设备支持；1项 #50 是作者自行关闭，未见说明原因，不能记为维护者拒绝。** 部分危险实现被明确排除，必须与协议贡献是否接纳分开。维护者声称保留作者提交的地方以下按公开说明记录；本地一些旧原始 SHA 对象不存在，不把 git ancestry 查询失败解释为丢失贡献。#62/#61 可用对象的原始提交已在本地 HEAD 历史确认。#50 原始和维护 SHA 均可查询且不是 HEAD 祖先。

## #62 FitBuds Turbo → #72：已吸收，但最终共享布局验证缺项

- 状态：#72 已于2026-09-07合并。关闭不是拒绝，原作者协议、明文BES、电量、四模式、游戏模式均保留。
- 维护修订：修复协议reset后旧游戏状态残留；噪声/游戏证据独立；标准三态、抗风噪、游戏开关共享卡片结构；严格单字节0/1游戏回包。
- 证据：[整理与待实机复核说明](https://github.com/silverpoetry/HyperEars/pull/62#issuecomment-5568802944)、[合入说明](https://github.com/silverpoetry/HyperEars/pull/62#issuecomment-5568896106)、[维护#72](https://github.com/silverpoetry/HyperEars/pull/72)。
- 当前代码：`integration/.../EdifierFitBudsTurboAdapter.kt`、`EdifierGameMode.kt:7-28`、`system-module/.../EdifierFitBudsTurboMiLinkCardAdapter.kt:9-24`，注册位于 `EarbudAdapter.kt:634`。原始 `6bd1312947c4f831197ba5129fbe9368adaeebf3` 是本地HEAD祖先。
- 行动：不要重复加型号。保留最终维护共享布局仍待真实Turbo复核；CONTRIBUTING明确记载提前合并#72/关闭#62及2026-09-08保留例外，绝非未来免复核授权。

## #61 Edifier单耳0值 → #71：已吸收且补齐边界

- 状态：维护#71于2026-09-07合并。
- 原始贡献是耳侧0转未知，原版会丢弃双耳均未知帧；维护版保留合法盒电量并清旧耳状态、盒离线清旧盒、整机/在线盒真0仍有效，先校验范围。
- 证据：[明确关闭/吸收说明](https://github.com/silverpoetry/HyperEars/pull/61#issuecomment-5568771500)、[#71](https://github.com/silverpoetry/HyperEars/pull/71)。
- 当前：`protocol/.../edifier/EdifierWireCodec.kt:128-146`、测试 `EdifierWireCodecTest.kt:117,147`；原始 `38127cc241469fc70244051552ba0c0be15fce5c` 是HEAD祖先。
- 行动：无需重新引入；后续统一电量状态保持“缺失字段”和“明确离线”的区别，不能恢复原始双耳null整帧丢弃逻辑。

## #55 Sony WF-1000XM6 / WH-1000XM4 → #57：已吸收且有维护版本实测

- 状态：#57于2026-08-31合并；不是拒绝Sony支持。
- 接纳XM6 0x19方言、提示音字节0x01、组件电量、XM4精确型号握手一次重发。
- 明确未接纳：所有设备握手超时2.5s改6s、每次预握手帧都重发、未过滤设备/UUID/socket的Sound Connect全原始流量Hook。这些是范围/生命周期风险，非设备协议否定。
- 证据：[维护边界](https://github.com/silverpoetry/HyperEars/pull/55#issuecomment-5467834728)、[作者五项维护版实测+确认音证据](https://github.com/silverpoetry/HyperEars/pull/55#issuecomment-5471531921)、[关闭说明](https://github.com/silverpoetry/HyperEars/pull/55#issuecomment-5481924079)。
- 当前：`integration/.../SonyEarbudAdapter.kt:114-145,346`与`docs/sony-headphones-protocol.md`对应。
- 行动：保留当前有界策略；高级Sony扩展是新范围，不应把已排除的全Socket Hook当作待整合功能。

## #54 FitClip Ultra盒电量/游戏 → #56 + #59：协议与最终原生模式卡已吸收

- #56于2026-08-30合入协议；#59于2026-08-31合入卡片最终方案。
- 原始作者推断`onReplyIsMmaHeadset(true)`即SYSTEM所有权不成立：该值可能已被HyperEars After Hook改写。维护者据修改前native-admission日志保留所有权保护，不按型号绕过。
- 最初插入游戏行造成“更多设置”裁切；高度修补同样失败，作者撤回仅凭日志得出的通过结论。最终删除高度累加/监听/修补，复用原生模式卡显示标准/游戏两项，但不虚构ANC协议能力。
- 证据：[仲裁纠正与维护边界](https://github.com/silverpoetry/HyperEars/pull/54#issuecomment-5467442462)、[#56实测](https://github.com/silverpoetry/HyperEars/pull/54#issuecomment-5467625979)、[#59作者撤回误报](https://github.com/silverpoetry/HyperEars/pull/59#issuecomment-5468177891)、[原生卡最终方案](https://github.com/silverpoetry/HyperEars/pull/59#issuecomment-5481339923)、[最终main实机复核](https://github.com/silverpoetry/HyperEars/pull/59#issuecomment-5487564014)、[关闭说明](https://github.com/silverpoetry/HyperEars/pull/54#issuecomment-5481398228)。
- 当前：`integration/.../EdifierEarbudAdapter.kt:190-202` GAME_MODE投影、`EdifierGameMode.kt`类型化状态/请求；盒电量与#61后续也已存在。
- 行动：这是本轮“真原生卡片”可复用的已验证经验，不能重新引回插入行/测量高度补丁。最终反馈明确确认显示、选中、回读、重开与重连；不要扩大成所有ROM永久验证。

## #53 FreeClip2 → #58：电量适配已吸收；原生Settings错误ANC入口明确遗留

- #58于2026-08-31合并。保留Class0x001F00准入、精确FreeClip2、channel1、电量专用profile；不发送噪声查询，CRC合法电量后开放。
- 未扩大未经同等证据的FreeLace全局名称准入；原版FreeClip继续家族候选，不自动继承精确型号画像。
- 关键未吸收需求：作者维护版验证通过但反馈“更多设置页多出降噪选项”。维护者明确归入MIUI蓝牙Settings页面，旧范围不加UI Hook；不是协议仍支持ANC。
- 证据：[维护边界](https://github.com/silverpoetry/HyperEars/pull/53#issuecomment-5467938936)、[作者反馈错误原生入口](https://github.com/silverpoetry/HyperEars/pull/58#issuecomment-5468204014)、[维护者边界解释](https://github.com/silverpoetry/HyperEars/pull/58#issuecomment-5481909063)、[关闭说明](https://github.com/silverpoetry/HyperEars/pull/53#issuecomment-5482009310)。
- 当前：`integration/.../HuaweiFreebudsProtocolAdapter.kt:104-121`电量专用精确适配已在。
- 行动：在本次“所有现有型号原生Settings语义一致”新目标里重新纳入这个已记录UI问题，按真实宿主能力隐藏无效ANC入口；不能为消除UI异常而开放不存在的设备ANC能力。

## #50 W820NB双金标：作者关闭，协议尚未吸收，优先恢复研究

- Timeline证明作者QNquenan于2026-08-27T10:42:27Z关闭，commit_id/state_reason为空；无明确关闭原因，无后续维护PR。API事件 <https://api.github.com/repos/silverpoetry/HyperEars/issues/events/30098030194> ，PR <https://github.com/silverpoetry/HyperEars/pull/50> 。不要写成“维护者拒绝该型号”或断言作者撤回了协议结论。
- 作者贡献 `d38eea5f8c9aa06ffc23480b3cc421113786266e`：独立BES V1 `[AA/BB/CC][LEN][CMD][PAYLOAD][sum16]`，无XOR，校验=8217+前序字节和（上游文案叫CRC16，数学上是加和校验）；0xD0整机电量，0xCC/0xC1三态（1标准、2ANC、3通透），0x08/09游戏。抓包及作者原版实测不等于维护版验证。
- 原始diff确有独立codec/tests/adapter/card；ANC第二字节通透档位已记录，但原PR CHANGELOG也写“暂不投影”，因此通透度不是已完成待捞回UI。
- **被明确暂缓的是卡片做法**：按文字寻找原生降噪/通透/关闭并设置isSelected/isActivated/alpha，绕过统一状态投影且绑定特定宿主View行为。
- 维护者已提供保留协议/游戏扩展、移除按钮扫描的`caa4958f4bf8a7b12d1cfaad69d7da00ddc3c700`，分支`codex/pr50-w820nb-rework`，并加入AncTrace定位设备状态→跨进程→MiLink回调→原生查询链路。作者随后上传录屏与日志，但现有评论没有维护者最终分析或最终版本通过结论。
- 证据：[明确局部UI异议与验证分支](https://github.com/silverpoetry/HyperEars/pull/50#issuecomment-5407328837)、[作者提交录屏/日志](https://github.com/silverpoetry/HyperEars/pull/50#issuecomment-5409087696)、[维护commit](https://github.com/silverpoetry/HyperEars/commit/caa4958f4bf8a7b12d1cfaad69d7da00ddc3c700)。
- 当前源码搜索`EdifierV1|DoubleGold|双金`无实现；只有W820系列BES家族推断。原始与维护commit均不是本地HEAD祖先；remote维护分支还存在。现有通用W820识别不等于支持此V1帧。
- 行动：用户批准实施后，先复核遗留录屏/日志以及rework完整差异，恢复精确V1候选和类型化游戏/可能的通透档位设计；迁入当前contract/reset/readback架构。保留贡献历史，建立Draft维护PR并等最终设备/原生卡实测。不要直接恢复572行旧CardAdapter；不要凭原版实测直接标记修复。

## #39 ROSE Ceramics X/Luli X → #45：已吸收，严格BLE匹配与复测后合入

- #45于2026-08-30合并。保留FDB3、FF16#21/FF17#23、WNR与四态。
- 排除只凭0x8418厂商键接受任意无名BLE设备；后据实际13字节载荷和主设备地址后两字节建立会话关联。扫描候选只读取，不主动探测认品牌。
- 中间“UUID仍旧”诊断来自混合旧进程日志，维护者按加载生命周期纠正；后有干净重测与有界候选日志，作者最终明确正常。
- 证据：[维护与原始匹配风险](https://github.com/silverpoetry/HyperEars/pull/39#issuecomment-5364880215)、[严格关联](https://github.com/silverpoetry/HyperEars/pull/39#issuecomment-5367783478)、[最终作者通过](https://github.com/silverpoetry/HyperEars/pull/39#issuecomment-5455948979)、[关闭说明](https://github.com/silverpoetry/HyperEars/pull/39#issuecomment-5467117341)。
- 当前：`integration/.../RoseEarbudAdapter.kt:121-177`实际端点与配置存在。
- 行动：不重复加型号，不恢复弱身份匹配/无证据动态UUID fallback；与其他ROSE型号扩展分开维护。

## #35 FreeBuds Pro3 → #43：已吸收；旧报告不等于完整最终维护版硬件证明

- #43于2026-08-21合并；原PR的Pro3档位、三态、电量、2B03同步与控制App贡献保留在维护链中。
- 维护统一Pro3/4/家族session、channel1/16探测、收紧Huawei范围、CRC合法后确认、只Pro3开专属档位，补全控制App元数据。原PR文案“CRC-8”错误不能照抄，实际当前为CRC16/XMODEM。
- 证据：[维护说明](https://github.com/silverpoetry/HyperEars/pull/35#issuecomment-5364879779)、[#43维护者验证说明](https://github.com/silverpoetry/HyperEars/pull/43#issuecomment-5370127648)、[关闭说明](https://github.com/silverpoetry/HyperEars/pull/35#issuecomment-5370130878)。
- 验证边界：可见#43评论引用原PR实测+源码交叉核对+CI，未见作者对最终维护提交的独立新设备回报；不能用“维护验证完成”措辞自动补齐此证据。
- 当前：`HuaweiFreebudsPro3Adapter.kt`、`HuaweiFreebudsProtocolAdapter.kt`、`protocol/.../huawei/HuaweiFreebudsSppCodec.kt`、`docs/huawei-freebuds-protocol.md`已实现。
- 行动：高级Huawei能力是本次新扩展；不重做已吸收基本协议。后续严格执行当前最终版本复核规则。

## #34 MOONDROP Pudding → #42：基础已吸收，明确未吸收功能与状态缺陷

- #42于2026-08-25合并。维护保留基础三态/电量，移除根据单侧电量推断佩戴后禁止控制；分侧合并、有界初始补查、一次写入+只读校正已在。
- 作者反馈基础项通过，但明确说暂不合并：缺少0x03抗风噪、固件3.5.5自适应ANC/主动上报尚未验证、单耳/盒离线旧电量残留。维护者随后选择先合入已验证最小范围，并把这些留给后续。此历史行为不能当作未来跳过当前贡献流程的授权。
- 证据：[基础维护](https://github.com/silverpoetry/HyperEars/pull/34#issuecomment-5364879548)、[作者列遗留项](https://github.com/silverpoetry/HyperEars/pull/34#issuecomment-5386635161)、[明确部分吸收与关闭](https://github.com/silverpoetry/HyperEars/pull/34#issuecomment-5407425473)。
- 当前核查：`integration/.../MoondropPuddingAdapter.kt:237-241`仍以`report.leftPercent?.let(...) ?: left`等合并，因此null不会清除同会话旧值；`:255`明确不支持WIND。`docs/moondrop-pudding-protocol.md:78-104`只有三态。现有codec支持已知主动电量帧，不等于已验证3.5.5新增上报协议。
- 行动：将抗风噪、3.5.5自适应/通知及离线电量清理纳入未吸收清单。电量修复需区分“本帧未出现字段”与“字段明确报告离线”，不能把所有null改为清空而破坏分片合并。

## #33 FitClip Ultra基础 → #41：已吸收且维护版实测

- #41于2026-08-21合并；准确归类维护接续。
- 原始`contains(fitclip)`过宽，维护收紧精确别名、能力门禁与reset测试；0xF2双耳电量、无ANC并不发会致断连的0xCC保留。
- 证据：[维护说明](https://github.com/silverpoetry/HyperEars/pull/33#issuecomment-5364879335)、[作者签名维护包实测](https://github.com/silverpoetry/HyperEars/pull/33#issuecomment-5365366457)、[关闭说明](https://github.com/silverpoetry/HyperEars/pull/33#issuecomment-5367709061)。
- 当前：`integration/.../EdifierEarbudAdapter.kt:190-216`，后续#54/#61补全盒/游戏/零值语义。
- 行动：已有适配，不恢复宽泛fitclip名称规则；统一原生UI仍保持真实无ANC。

## 后续报告应优先呈现的遗漏

1. 真正未吸收型号：#50 W820NB双金标BES V1，不是“W820家族已经有”即可覆盖。
2. 真正功能/状态遗漏：#34 Pudding抗风噪、3.5.5自适应/上报与明确离线旧电量清理。
3. 新目标应接回的UI遗漏：#53/#58 FreeClip2原生Settings错误ANC入口。
4. 验证缺项：#62/#72 Turbo最终共享卡片；#35/#43可见记录未区分最终维护版新硬件复核。#54/#59最终原生卡已有后续真实设备反馈，不再笼统列作未验证。
5. 已有风险处置不应复活：按文字/alpha改原生按钮、绕系统原生所有权、无界重发、全局超时扩大、未过滤原始Socket Hook、弱BLE厂商键匹配、无证据佩戴推断、插入行高度修补。

原始JSON、维护JSON、#50 diff/timeline已保存同目录供复核。以上属于审查报告，不构成任何实施或PR状态变更。
