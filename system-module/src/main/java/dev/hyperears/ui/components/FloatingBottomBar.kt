// Adapted from KernelSU 4521784328352c54334beb29e05c74360b60d7cb (GPL-3.0).
// Adapted from compose-miuix-ui's floating navigation example — Apache 2.0.

package dev.hyperears.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastRoundToInt
import kotlinx.coroutines.flow.collectLatest
import dev.hyperears.ui.components.miuix.animation.NavigationIndicatorState
import dev.hyperears.ui.components.miuix.modifier.inspectDragGestures
import dev.hyperears.ui.theme.isHyperEarsDarkTheme
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val FLOATING_BAR_BLUR_RADIUS = 40f
private val FLOATING_TAB_MIN_WIDTH = 84.dp

@Composable
internal fun RowScope.FloatingBottomBarItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .defaultMinSize(minWidth = FLOATING_TAB_MIN_WIDTH)
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .fillMaxHeight()
            .weight(1f),
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}

@Composable
internal fun FloatingBottomBar(
    modifier: Modifier = Modifier,
    selectedIndex: () -> Int,
    onSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    backgroundBlurEnabled: Boolean,
    content: @Composable RowScope.() -> Unit
) {
    val isInDark = isHyperEarsDarkTheme()
    val pillShape = remember { CircleShape }
    val tabContentColor = MiuixTheme.colorScheme.onSurface
    val surfaceContainer = MiuixTheme.colorScheme.surfaceContainer
    val selectionColor = if (isInDark) {
        Color.White.copy(alpha = 0.12f)
    } else {
        Color.Black.copy(alpha = 0.08f)
    }

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()

    var tabWidthPx by remember { mutableFloatStateOf(0f) }

    val indicatorState = remember(animationScope, tabsCount) {
        NavigationIndicatorState(
            coroutineScope = animationScope,
            initialValue = selectedIndex().toFloat(),
            valueRange = 0f..(tabsCount - 1).toFloat(),
        )
    }

    LaunchedEffect(selectedIndex, indicatorState) {
        snapshotFlow { selectedIndex() }.collectLatest { index ->
            indicatorState.animateTo(index.toFloat())
        }
    }

    Box(
        modifier = modifier.width(IntrinsicSize.Min),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            Modifier
                .onGloballyPositioned { coords ->
                    val contentWidthPx = coords.size.width.toFloat() -
                        with(density) { 8.dp.toPx() }
                    tabWidthPx = (contentWidthPx / tabsCount).coerceAtLeast(0f)
                }
                .dropShadow(
                    shape = pillShape,
                    shadow = Shadow(
                        radius = 10.dp,
                        color = Color.Black,
                        alpha = if (isInDark) 0.2f else 0.1f,
                    ),
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .then(
                    if (backgroundBlurEnabled) {
                        Modifier.textureBlur(
                            backdrop = backdrop,
                            shape = pillShape,
                            blurRadius = FLOATING_BAR_BLUR_RADIUS,
                            colors = BlurColors(
                                blendColors = listOf(
                                    BlendColorEntry(surfaceContainer.copy(alpha = 0.72f)),
                                ),
                            ),
                        )
                    } else {
                        Modifier.background(surfaceContainer, pillShape)
                    }
                )
                .height(64.dp)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides tabContentColor) {
                content()
            }
        }

        if (tabWidthPx > 0f) {
            val tabWidthDp = with(density) { tabWidthPx.toDp() }
            val indicatorModifier = Modifier
                .padding(horizontal = 4.dp)
                .graphicsLayer {
                    val progressOffset = indicatorState.value * tabWidthPx
                    translationX = if (isLtr) progressOffset else -progressOffset
                }
                .pointerInput(indicatorState, tabWidthPx, isLtr) {
                    fun settleSelection() {
                        val targetIndex = indicatorState.value
                            .fastRoundToInt()
                            .coerceIn(0, tabsCount - 1)
                        indicatorState.animateTo(targetIndex.toFloat())
                        onSelected(targetIndex)
                    }
                    inspectDragGestures(
                        onDragStart = { indicatorState.stopAnimation() },
                        onDragEnd = { settleSelection() },
                        onDragCancel = { settleSelection() },
                    ) { _, dragAmount ->
                        if (tabWidthPx > 0f) {
                            val direction = if (isLtr) 1f else -1f
                            indicatorState.dragBy(dragAmount.x / tabWidthPx * direction)
                        }
                    }
                }
                .height(56.dp)
                .width(tabWidthDp)

            Box(
                modifier = indicatorModifier
                    .clip(pillShape)
                    .background(selectionColor, pillShape),
            )
        }
    }
}
