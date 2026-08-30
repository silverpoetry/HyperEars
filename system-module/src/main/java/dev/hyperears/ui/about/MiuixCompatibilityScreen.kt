package dev.hyperears.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hyperears.ui.components.MiuixHyperEarsPage
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MiuixCompatibilityScreen(onNavigateBack: () -> Unit) {
    MiuixHyperEarsPage(
        title = "兼容性",
        onNavigateBack = onNavigateBack,
    ) { pagePadding, scrollBehavior ->
        var query by rememberSaveable { mutableStateOf("") }
        var searchExpanded by rememberSaveable { mutableStateOf(false) }
        val filteredBrands = remember(query) { filterSupportBrands(query) }
        val focusManager = LocalFocusManager.current
        val closeSearch = {
            query = ""
            searchExpanded = false
            focusManager.clearFocus()
        }

        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier
                .fillMaxSize()
                .padding(pagePadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "search") {
                SearchBar(
                    inputField = {
                        InputField(
                            query = query,
                            onQueryChange = { query = it },
                            onSearch = {
                                searchExpanded = false
                                focusManager.clearFocus()
                            },
                            expanded = searchExpanded,
                            onExpandedChange = { searchExpanded = it },
                            label = "搜索品牌、型号或能力",
                        )
                    },
                    expanded = searchExpanded,
                    onExpandedChange = { searchExpanded = it },
                    outsideEndAction = {
                        Text(
                            text = "取消",
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .clickable(onClick = closeSearch),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    },
                ) {}
            }
            if (filteredBrands.isEmpty()) {
                item(key = "empty") { MiuixCompatibilityEmptyResult() }
            } else {
                items(filteredBrands, key = SupportBrand::name) { brand ->
                    MiuixCompatibilityBrandCard(brand)
                }
            }
        }
    }
}

@Composable
private fun MiuixCompatibilityBrandCard(brand: SupportBrand) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = brand.name,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 2.dp),
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            brand.entries.forEach { entry ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = entry.name,
                            modifier = Modifier.weight(1f),
                            style = MiuixTheme.textStyles.body1,
                            fontWeight = FontWeight.Medium,
                        )
                        MiuixCompatibilityEvidenceBadge(entry.evidence)
                    }
                    Text(
                        text = "电量：${entry.battery.label} · 噪声：${entry.noiseControl}",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

@Composable
private fun MiuixCompatibilityEvidenceBadge(evidence: EvidenceLevel) {
    val colors = evidenceBadgeColors(evidence, isSystemInDarkTheme())
    Text(
        text = evidence.label,
        modifier = Modifier
            .background(colors.container, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        style = MiuixTheme.textStyles.footnote1,
        color = colors.content,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun MiuixCompatibilityEmptyResult() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "未找到匹配项",
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "尝试其他品牌、型号或能力关键词",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
