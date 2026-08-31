package dev.hyperears.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import dev.hyperears.BuildConfig
import dev.hyperears.ui.components.MiuixHyperEarsPage
import dev.hyperears.update.ReleaseInfo
import dev.hyperears.update.UpdateCheckUiState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Miuix renderer for the same project information used by [AboutScreen]. */
@Composable
fun MiuixAboutScreen(
    updateCheckState: UpdateCheckUiState,
    onCheckUpdates: () -> Unit,
    onOpenRelease: (ReleaseInfo) -> Unit,
    onOpenCompatibility: () -> Unit,
    bottomContentPadding: Dp = 0.dp,
) {
    MiuixHyperEarsPage(title = "关于") { pagePadding, scrollBehavior ->
        val uriHandler = LocalUriHandler.current
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier
                .fillMaxSize()
                .padding(pagePadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 12.dp,
                bottom = bottomContentPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "introduction") {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · GPL-3.0-only",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = "为第三方蓝牙耳机补充 HyperOS 与 MiLink 系统集成。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            item(key = "project-links") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = "兼容性",
                        summary = "查看支持品牌、型号与能力",
                        onClick = onOpenCompatibility,
                    )
                    projectLinks.forEach { link ->
                        BasicComponent(
                            title = link.title,
                            summary = link.detail,
                            onClick = { runCatching { uriHandler.openUri(link.url) } },
                        )
                    }
                    BasicComponent(
                        title = "检查更新",
                        summary = updateCheckState.detailText(),
                        enabled = !updateCheckState.checking,
                        onClick = {
                            updateCheckState.openOrCheck(onCheckUpdates, onOpenRelease)
                        },
                    )
                }
            }
            item(key = "copyright") {
                Text(
                    text = "© 2026 HyperEars contributors\n产品名称与商标归各自权利人所有。",
                    modifier = Modifier.padding(horizontal = 12.dp),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}
