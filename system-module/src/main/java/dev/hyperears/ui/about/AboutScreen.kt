package dev.hyperears.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import dev.hyperears.BuildConfig
import dev.hyperears.ui.components.HyperEarsPage
import dev.hyperears.update.ReleaseInfo
import dev.hyperears.update.UpdateCheckResult
import dev.hyperears.update.UpdateCheckUiState

internal data class SupportEntry(
    val name: String,
    val evidence: EvidenceLevel,
    val battery: BatteryCapability,
    val noiseControl: String,
)

internal data class SupportBrand(
    val name: String,
    val entries: List<SupportEntry>,
)

internal enum class EvidenceLevel(val label: String) {
    VERIFIED("实机验证"),
    PUBLIC_IMPLEMENTATION("公开实现"),
    REFERENCE_PROTOCOL("参考协议"),
    FAMILY_PROBE("家族探测"),
    STANDARD_FALLBACK("标准回退"),
}

internal enum class BatteryCapability(val label: String) {
    COMPONENT("组件电量"),
    LEFT_RIGHT("左右耳电量"),
    DEVICE("整机电量"),
    AGGREGATE("聚合电量"),
    DEVICE_OR_COMPONENT("整机或组件"),
    DEVICE_OR_AGGREGATE("整机或聚合"),
    SYSTEM("系统电量"),
}

internal data class ProjectLink(
    val title: String,
    val detail: String,
    val url: String,
)

internal val supportBrands = listOf(
    SupportBrand(
        name = "vivo / iQOO",
        entries = listOf(
            SupportEntry(
                name = "vivo TWS Air3 Pro",
                evidence = EvidenceLevel.VERIFIED,
                battery = BatteryCapability.COMPONENT,
                noiseControl = "降噪 / 关闭 / 通透",
            ),
            SupportEntry(
                name = "vivo TWS 3e",
                evidence = EvidenceLevel.PUBLIC_IMPLEMENTATION,
                battery = BatteryCapability.COMPONENT,
                noiseControl = "降噪 / 关闭 / 通透",
            ),
            SupportEntry(
                name = "其他 vivo / iQOO TWS",
                evidence = EvidenceLevel.FAMILY_PROBE,
                battery = BatteryCapability.COMPONENT,
                noiseControl = "降噪 / 关闭 / 通透",
            ),
        ),
    ),
    SupportBrand(
        name = "OPPO Enco",
        entries = listOf(
            SupportEntry(
                name = "Enco Air2 Pro",
                evidence = EvidenceLevel.REFERENCE_PROTOCOL,
                battery = BatteryCapability.COMPONENT,
                noiseControl = "降噪 / 关闭 / 通透",
            ),
            SupportEntry(
                name = "Enco Free4 / X3 / Air5",
                evidence = EvidenceLevel.REFERENCE_PROTOCOL,
                battery = BatteryCapability.COMPONENT,
                noiseControl = "降噪 / 关闭 / 通透",
            ),
            SupportEntry(
                name = "其他 OPPO / Enco 耳机",
                evidence = EvidenceLevel.FAMILY_PROBE,
                battery = BatteryCapability.COMPONENT,
                noiseControl = "降噪 / 关闭 / 通透",
            ),
        ),
    ),
    SupportBrand(
        name = "StarRing / 籁特易耳",
        entries = listOf(
            SupportEntry(
                name = "StarRing Ultra",
                evidence = EvidenceLevel.VERIFIED,
                battery = BatteryCapability.COMPONENT,
                noiseControl = "降噪 / 关闭 / 通透 / 抗风噪",
            ),
            SupportEntry(
                name = "其他 StarRing / 籁特易耳耳机",
                evidence = EvidenceLevel.STANDARD_FALLBACK,
                battery = BatteryCapability.SYSTEM,
                noiseControl = "无",
            ),
        ),
    ),
    SupportBrand(
        name = "Bose",
        entries = listOf(
            SupportEntry(
                name = "QuietComfort Headphones",
                evidence = EvidenceLevel.VERIFIED,
                battery = BatteryCapability.DEVICE,
                noiseControl = "降噪 / 通透 / 抗风噪",
            ),
            SupportEntry(
                name = "QuietComfort 35 / 35 II",
                evidence = EvidenceLevel.PUBLIC_IMPLEMENTATION,
                battery = BatteryCapability.DEVICE,
                noiseControl = "降噪 / 关闭 / 抗风噪",
            ),
            SupportEntry(
                name = "Noise Cancelling Headphones 700",
                evidence = EvidenceLevel.PUBLIC_IMPLEMENTATION,
                battery = BatteryCapability.DEVICE,
                noiseControl = "降噪 / 关闭 / 通透",
            ),
            SupportEntry(
                name = "QuietComfort 45 / QuietComfort Earbuds",
                evidence = EvidenceLevel.PUBLIC_IMPLEMENTATION,
                battery = BatteryCapability.DEVICE_OR_COMPONENT,
                noiseControl = "降噪 / 通透",
            ),
            SupportEntry(
                name = "QuietComfort Earbuds II / Ultra 系列",
                evidence = EvidenceLevel.REFERENCE_PROTOCOL,
                battery = BatteryCapability.DEVICE_OR_COMPONENT,
                noiseControl = "降噪 / 通透",
            ),
            SupportEntry(
                name = "其他 Bose BMAP 耳机",
                evidence = EvidenceLevel.FAMILY_PROBE,
                battery = BatteryCapability.DEVICE_OR_COMPONENT,
                noiseControl = "降噪 / 关闭 / 通透 / 抗风噪（按型号）",
            ),
        ),
    ),
    SupportBrand(
        name = "Edifier / 漫步者",
        entries = listOf(
            SupportEntry(
                name = "W860NB PRO",
                evidence = EvidenceLevel.VERIFIED,
                battery = BatteryCapability.DEVICE,
                noiseControl = "降噪 / 关闭 / 通透 / 抗风噪",
            ),
            SupportEntry(
                name = "花再 Evo Pro",
                evidence = EvidenceLevel.VERIFIED,
                battery = BatteryCapability.LEFT_RIGHT,
                noiseControl = "降噪 / 关闭 / 通透 / 抗风噪",
            ),
            SupportEntry(
                name = "W820 / W830 / W860 系列",
                evidence = EvidenceLevel.FAMILY_PROBE,
                battery = BatteryCapability.DEVICE,
                noiseControl = "降噪 / 关闭 / 通透 / 抗风噪",
            ),
            SupportEntry(
                name = "其他 Edifier 耳机",
                evidence = EvidenceLevel.FAMILY_PROBE,
                battery = BatteryCapability.DEVICE_OR_AGGREGATE,
                noiseControl = "降噪 / 关闭 / 通透 / 抗风噪",
            ),
        ),
    ),
    SupportBrand(
        name = "ROSESELSA / 弱水时砂",
        entries = listOf(
            SupportEntry(
                name = "EARFREE i5",
                evidence = EvidenceLevel.PUBLIC_IMPLEMENTATION,
                battery = BatteryCapability.COMPONENT,
                noiseControl = "降噪 / 关闭 / 通透 / 抗风噪",
            ),
            SupportEntry(
                name = "Furina Endless Solo of Solitude",
                evidence = EvidenceLevel.VERIFIED,
                battery = BatteryCapability.COMPONENT,
                noiseControl = "降噪 / 关闭 / 通透 / 抗风噪",
            ),
            SupportEntry(
                name = "ROSE Ceramics Ultra（琉璃 Ultra）",
                evidence = EvidenceLevel.VERIFIED,
                battery = BatteryCapability.COMPONENT,
                noiseControl = "降噪 / 关闭 / 通透 / 抗风噪",
            ),
            SupportEntry(
                name = "ROSE BudsFeel MK2",
                evidence = EvidenceLevel.PUBLIC_IMPLEMENTATION,
                battery = BatteryCapability.COMPONENT,
                noiseControl = "降噪 / 关闭 / 通透 / 抗风噪",
            ),
            SupportEntry(
                name = "EARFREE / EARFEEL / BudsFeel 产品线",
                evidence = EvidenceLevel.FAMILY_PROBE,
                battery = BatteryCapability.COMPONENT,
                noiseControl = "降噪 / 关闭 / 通透 / 抗风噪",
            ),
            SupportEntry(
                name = "其他 ROSESELSA / ROSE 耳机",
                evidence = EvidenceLevel.STANDARD_FALLBACK,
                battery = BatteryCapability.SYSTEM,
                noiseControl = "无",
            ),
        ),
    ),
    SupportBrand(
        name = "NiceHCK / YuanDao",
        entries = listOf(
            SupportEntry(
                name = "NiceHCK YuanDao OriG in",
                evidence = EvidenceLevel.PUBLIC_IMPLEMENTATION,
                battery = BatteryCapability.COMPONENT,
                noiseControl = "降噪 / 关闭 / 通透 / 抗风噪",
            ),
            SupportEntry(
                name = "其他 NiceHCK / YuanDao 耳机",
                evidence = EvidenceLevel.STANDARD_FALLBACK,
                battery = BatteryCapability.SYSTEM,
                noiseControl = "无",
            ),
        ),
    ),
    SupportBrand(
        name = "MOONDROP / 水月雨",
        entries = listOf(
            SupportEntry(
                name = "Robin / 知更鸟",
                evidence = EvidenceLevel.PUBLIC_IMPLEMENTATION,
                battery = BatteryCapability.LEFT_RIGHT,
                noiseControl = "降噪 / 关闭 / 通透",
            ),
            SupportEntry(
                name = "Pudding",
                evidence = EvidenceLevel.VERIFIED,
                battery = BatteryCapability.COMPONENT,
                noiseControl = "降噪 / 关闭 / 通透",
            ),
            SupportEntry(
                name = "其他 MOONDROP / 水月雨耳机",
                evidence = EvidenceLevel.STANDARD_FALLBACK,
                battery = BatteryCapability.SYSTEM,
                noiseControl = "无",
            ),
        ),
    ),
    SupportBrand(
        name = "荣耀",
        entries = listOf(
            SupportEntry(
                name = "荣耀亲选耳机 X5s Pro",
                evidence = EvidenceLevel.VERIFIED,
                battery = BatteryCapability.COMPONENT,
                noiseControl = "降噪 / 关闭 / 通透",
            ),
        ),
    ),
    SupportBrand(
        name = "华为",
        entries = listOf(
            SupportEntry(
                name = "HUAWEI FreeBuds Pro 3",
                evidence = EvidenceLevel.VERIFIED,
                battery = BatteryCapability.COMPONENT,
                noiseControl = "降噪 / 关闭 / 通透",
            ),
            SupportEntry(
                name = "HUAWEI FreeBuds 4",
                evidence = EvidenceLevel.PUBLIC_IMPLEMENTATION,
                battery = BatteryCapability.COMPONENT,
                noiseControl = "降噪 / 关闭",
            ),
            SupportEntry(
                name = "其他 FreeBuds / FreeClip / FreeLace",
                evidence = EvidenceLevel.FAMILY_PROBE,
                battery = BatteryCapability.COMPONENT,
                noiseControl = "降噪 / 关闭 / 通透",
            ),
        ),
    ),
    SupportBrand(
        name = "QCY",
        entries = listOf(
            SupportEntry(
                name = "Crossky C50S / QYCC50S",
                evidence = EvidenceLevel.PUBLIC_IMPLEMENTATION,
                battery = BatteryCapability.COMPONENT,
                noiseControl = "降噪 / 关闭 / 通透",
            ),
            SupportEntry(
                name = "其他名称符合 QCY / Crossky 规则的耳机",
                evidence = EvidenceLevel.FAMILY_PROBE,
                battery = BatteryCapability.COMPONENT,
                noiseControl = "降噪 / 关闭 / 通透",
            ),
        ),
    ),
    SupportBrand(
        name = "Sony",
        entries = listOf(
            SupportEntry(
                name = "WH-1000XM2 / XM3 / XM4、WF-1000XM3 / XM4、WI-SP600N",
                evidence = EvidenceLevel.PUBLIC_IMPLEMENTATION,
                battery = BatteryCapability.DEVICE_OR_COMPONENT,
                noiseControl = "降噪 / 关闭 / 通透 / 抗风噪",
            ),
            SupportEntry(
                name = "WH-1000XM5 / XM6、CH720N、ULT WEAR、WF-1000XM5、SP800N、C700N / C710N、LinkBuds S",
                evidence = EvidenceLevel.PUBLIC_IMPLEMENTATION,
                battery = BatteryCapability.DEVICE_OR_COMPONENT,
                noiseControl = "降噪 / 关闭 / 通透",
            ),
            SupportEntry(
                name = "WF-C510",
                evidence = EvidenceLevel.PUBLIC_IMPLEMENTATION,
                battery = BatteryCapability.COMPONENT,
                noiseControl = "关闭 / 通透",
            ),
            SupportEntry(
                name = "WF-C500 / LinkBuds / WI-C100",
                evidence = EvidenceLevel.PUBLIC_IMPLEMENTATION,
                battery = BatteryCapability.DEVICE_OR_COMPONENT,
                noiseControl = "无",
            ),
            SupportEntry(
                name = "其他 Sony 降噪产品线",
                evidence = EvidenceLevel.FAMILY_PROBE,
                battery = BatteryCapability.DEVICE_OR_COMPONENT,
                noiseControl = "降噪 / 关闭 / 通透",
            ),
            SupportEntry(
                name = "其他 Sony 标准耳机",
                evidence = EvidenceLevel.STANDARD_FALLBACK,
                battery = BatteryCapability.SYSTEM,
                noiseControl = "无",
            ),
        ),
    ),
    SupportBrand(
        name = "通用蓝牙耳机",
        entries = listOf(
            SupportEntry(
                name = "标准 A2DP / HFP 耳机",
                evidence = EvidenceLevel.STANDARD_FALLBACK,
                battery = BatteryCapability.SYSTEM,
                noiseControl = "无",
            ),
        ),
    ),
)

internal val projectLinks = listOf(
    ProjectLink(
        title = "源代码",
        detail = "github.com/silverpoetry/HyperEars",
        url = "https://github.com/silverpoetry/HyperEars",
    ),
    ProjectLink(
        title = "问题反馈",
        detail = "提交兼容性问题或功能建议",
        url = "https://github.com/silverpoetry/HyperEars/issues/new/choose",
    ),
    ProjectLink(
        title = "开源许可",
        detail = "GNU GPL-3.0-only",
        url = "https://github.com/silverpoetry/HyperEars/blob/main/LICENSE",
    ),
    ProjectLink(
        title = "第三方声明",
        detail = "协议来源与第三方许可",
        url = "https://github.com/silverpoetry/HyperEars/blob/main/THIRD_PARTY_NOTICES.md",
    ),
    ProjectLink(
        title = "隐私说明",
        detail = "本地数据处理与权限边界",
        url = "https://github.com/silverpoetry/HyperEars/blob/main/PRIVACY.md",
    ),
)

internal fun UpdateCheckUiState.detailText(): String = when {
    checking -> "正在检查更新"
    result is UpdateCheckResult.Available -> "发现新版本 ${result.release.version}"
    result == UpdateCheckResult.UpToDate -> "当前已是最新版本"
    result is UpdateCheckResult.Failed -> result.message
    else -> "从 GitHub Releases 获取最新版本"
}

internal fun UpdateCheckUiState.openOrCheck(
    onCheck: () -> Unit,
    onOpenRelease: (ReleaseInfo) -> Unit,
) {
    if (checking) return
    val available = result as? UpdateCheckResult.Available
    if (available == null) onCheck() else onOpenRelease(available.release)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    updateCheckState: UpdateCheckUiState,
    onCheckUpdates: () -> Unit,
    onOpenRelease: (ReleaseInfo) -> Unit,
    onOpenCompatibility: () -> Unit,
    bottomContentPadding: Dp = 0.dp,
) {
    HyperEarsPage(title = "关于") { pagePadding, scrollBehavior ->
        val uriHandler = LocalUriHandler.current
        val listState = rememberLazyListState()

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(pagePadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = bottomContentPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
        item(key = "header") {
            CenteredContent { modifier ->
                Column(
                    modifier = modifier,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · GPL-3.0-only",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "为第三方蓝牙耳机补充 HyperOS 与 MiLink 系统集成。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item(key = "project") {
            CenteredContent { modifier ->
                Column(
                    modifier = modifier,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Column {
                            ListItem(
                                headlineContent = { Text("兼容性") },
                                supportingContent = { Text("查看支持品牌、型号与能力") },
                                colors = ListItemDefaults.colors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                ),
                                modifier = Modifier.clickable(onClick = onOpenCompatibility),
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                            projectLinks.forEach { link ->
                                ListItem(
                                    headlineContent = { Text(link.title) },
                                    supportingContent = { Text(link.detail) },
                                    colors = ListItemDefaults.colors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    ),
                                    modifier = Modifier.clickable {
                                        runCatching { uriHandler.openUri(link.url) }
                                    },
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                            ListItem(
                                headlineContent = { Text("检查更新") },
                                supportingContent = { Text(updateCheckState.detailText()) },
                                colors = ListItemDefaults.colors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                ),
                                modifier = Modifier.clickable(enabled = !updateCheckState.checking) {
                                    updateCheckState.openOrCheck(onCheckUpdates, onOpenRelease)
                                },
                            )
                        }
                    }
                }
            }
        }
            item(key = "copyright") {
                CenteredContent { modifier ->
                    Text(
                        text = "© 2026 HyperEars contributors\n产品名称与商标归各自权利人所有。",
                        modifier = modifier,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CenteredContent(content: @Composable (Modifier) -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        content(Modifier.fillMaxWidth().widthIn(max = 800.dp))
    }
}
