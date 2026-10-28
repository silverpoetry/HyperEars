# 已关闭依赖 PR 审计（2026-10-28，只读）

范围：closed-prs.json 中 12 个未以 GitHub PR merge 状态结束的依赖 PR。读取各 PR comments/reviews；对 #63/#64/#3/#9 补充 timeline；核对当前 gradle/libs.versions.toml、三个 workflow 及实际历史提交。**12 个均没有明确技术拒绝证据；不能把 closed/unmerged 统计为拒绝。** 没有执行升级、构建、安装或修改 PR。

## 逐项结论

- [#64 Navigation 2.9.8→2.10.0](https://github.com/silverpoetry/HyperEars/pull/64)：**合并维护版承接，非拒绝**。[#70](https://github.com/silverpoetry/HyperEars/pull/70) 明确保留 Dependabot 提交并整体更新，正文包含 Closes #64；timeline 显示维护者在 2026-09-07 09:32:19Z 关闭。当前 Navigation **2.10.2**，后续 #84 升级。机器人“不会再次提醒此版本”仅为关闭后的默认答复，不是维护者否定说明。
- [#63 AGP 9.3.1→9.4.0](https://github.com/silverpoetry/HyperEars/pull/63)：**合并维护版承接，非拒绝**。#70 说明原始 AGP job 在 Gradle9.5.0 下版本检查失败，维护版将 wrapper 升至要求的9.6.0，再组合验证通过；保留作者提交。与#64同秒关闭。当前 AGP **9.4.1**（#84）。兼容性问题已经通过组合维护解决，不能写成 AGP9.4 被永久拒绝。
- [#29 Kotlin 2.2.10→2.4.10](https://github.com/silverpoetry/HyperEars/pull/29)：**主线已更新而过时**。[关闭说明](https://github.com/silverpoetry/HyperEars/pull/29#issuecomment-5414863633)为 dependencies are up-to-date。实际主线提交 [bd17a3a](https://github.com/silverpoetry/HyperEars/commit/bd17a3a5864cb81adafe4dad1d7fdb1224d87798)已升级到2.4.10。当前 **2.4.20**；Compose/serialization插件共用版本。
- [#15 setup-java v4→5.6.0](https://github.com/silverpoetry/HyperEars/pull/15)：**被主线更高版本覆盖**。[机器人确认已最新](https://github.com/silverpoetry/HyperEars/pull/15#issuecomment-5232996556)。提交 [a9620e9](https://github.com/silverpoetry/HyperEars/commit/a9620e9208184cc59ae67a62c9e789c3db2e1790)直接把 actions/setup-java 升至5.7.0。当前三个 workflow 都为 **v6.0.1**。
- [#9 Compose插件2.2.10→2.4.10](https://github.com/silverpoetry/HyperEars/pull/9)：**更新路径替代**。[机器人说明](https://github.com/silverpoetry/HyperEars/pull/9#issuecomment-5225242929)“updatable in another way”，timeline 为 Dependabot关闭，未记录具体替代PR编号。当前该插件通过版本目录共享 **Kotlin2.4.20**；后来 #29 是聚合 kotlin 更新，但不把推测当成该关闭事件的明确指定关系。
- [#8 gradle/actions v4→v6](https://github.com/silverpoetry/HyperEars/pull/8)：**主线已更新而过时**。[机器人说明](https://github.com/silverpoetry/HyperEars/pull/8#issuecomment-5232998459)。a9620e9已经同时升级wrapper-validation/setup-gradle；当前三个 workflow 仍为 **v6**。
- [#7 AGP9.3.1→9.3.2](https://github.com/silverpoetry/HyperEars/pull/7)：**被较新提案替代**。[明确 Superseded by #63](https://github.com/silverpoetry/HyperEars/pull/7#issuecomment-5496232879)。链路 #7→#63→维护#70→后续#84，当前 **9.4.1**。
- [#6 upload-artifact v4→v7](https://github.com/silverpoetry/HyperEars/pull/6)：**主线已更新而过时**。[机器人说明](https://github.com/silverpoetry/HyperEars/pull/6#issuecomment-5232996177)。a9620e9包含该更新；当前CI/signed-build为 **v7**（release没有此步骤）。
- [#4 checkout v4→v7](https://github.com/silverpoetry/HyperEars/pull/4)：**主线已更新而过时**。[机器人说明](https://github.com/silverpoetry/HyperEars/pull/4#issuecomment-5232995673)。a9620e9包含该更新；当前三个 workflow 为 **v7**。
- [#3 lifecycle2.10.0→2.11.0](https://github.com/silverpoetry/HyperEars/pull/3)：**主线已经采用目标，机器人作废关闭**。[机器人原话](https://github.com/silverpoetry/HyperEars/pull/3#issuecomment-5414864918)是“no longer updatable”，没有写技术不兼容。实际bd17a3a明确升级至2.11.0；当前仍为 **2.11.0**。因此可确定并非功能被否决，不需要猜测该机器人措辞的内部原因。
- [#2 setup-java v4→v5](https://github.com/silverpoetry/HyperEars/pull/2)：**被较新提案替代**。[明确 Superseded by #15](https://github.com/silverpoetry/HyperEars/pull/2#issuecomment-5152035927)。后者又被主线5.7.0覆盖，当前 **v6.0.1**。
- [#1 core-ktx1.18.0→1.19.0](https://github.com/silverpoetry/HyperEars/pull/1)：**主线已更新而过时**。[机器人说明](https://github.com/silverpoetry/HyperEars/pull/1#issuecomment-5414863860)。bd17a3a包含该升级；当前仍为 **1.19.0**。

历史缺少 dependencies 标签的机器人告警出现在多数早期 PR；它说明自动标签配置不完整，不是依赖升级被拒绝的证据。没有人为 review 写明“不接受此功能/版本”的证据。当前依赖已达到或超过这12项的请求目标，因此本审计不提出重开或重复升级。

## 已合并 PR 的交叉引用补充

扫描59个已合并PR的title/body，确认以下承接关系，供设备审计交叉检查：

- #72 承接 #62，并正文 Closes #62；同时提到 #61。需沿用 CONTRIBUTING 记录的提前合并例外，不能将原始硬件结果当维护版已验证。
- #71 正文 Closes #61。
- #58←#53（FreeClip2）；#57←#55（Sony）；#56←#54（FitClipUltra游戏模式）。
- #45←#39（CeramicsX）；#43←#35（FreeBuds）；#42←#34（Pudding）；#41←#33（FitClipUltra）。
- #50未在这59份正文中发现明确 #50 承接引用；需以设备PR评论/提交证据判断，不能用“无正文引用”推导未采用。
- #65依赖机器人body命中#62来自上游发布说明的编号，**不是本仓库设备PR关系**，已排除。
- 其他已合并维护关系：#89纳入#86/#87/#88，并保留#75/#74/#83提交；#85维护#76。这些不是本组12依赖PR，但说明“维护版合入”常保留来源PR正文历史状态描述，不应仅据旧正文Draft文字判断当前状态。

本报告证据文件：pr-{编号}-detail.json；pr-63/pr-64/pr-3/pr-9-timeline.json。当前版本对应 gradle/libs.versions.toml:2-8；workflow动作版本在 ci.yml:24-58、signed-build.yml:26-95、release.yml:23-35。源代码审计和PR审计仅产出报告，实施变更须另获用户批准。
