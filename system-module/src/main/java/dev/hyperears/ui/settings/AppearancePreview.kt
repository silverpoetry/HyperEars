package dev.hyperears.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.hyperears.ui.theme.UiPreferences
import dev.hyperears.ui.theme.UiStyle

/**
 * Static layout preview only. The live bar is rendered by the real Material or KernelSU-aligned
 * Miuix component; the preview deliberately does not reimplement the navigation behavior.
 */
@Composable
internal fun AppearancePreview(
    preferences: UiPreferences,
    backgroundColor: Color,
    surfaceColor: Color,
    cardColor: Color,
    accentColor: Color,
    contentColor: Color,
) {
    val isMiuix = preferences.style == UiStyle.MIUIX
    val floating = isMiuix && preferences.floatingNavigationBar
    val translucent = isMiuix && preferences.navigationBlur
    val navigationShape = if (floating) CircleShape else RoundedCornerShape(0.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(176.dp)
                .aspectRatio(0.56f)
                .shadow(4.dp, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .background(backgroundColor)
                .border(1.dp, contentColor.copy(alpha = 0.12f), RoundedCornerShape(24.dp)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 74.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(72.dp)
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(contentColor.copy(alpha = 0.82f)),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(accentColor.copy(alpha = 0.16f)),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(2) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(cardColor),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(cardColor),
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .then(
                        if (floating) {
                            Modifier
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .shadow(5.dp, navigationShape)
                                .clip(navigationShape)
                        } else {
                            Modifier.fillMaxWidth()
                        },
                    )
                    .height(if (floating) 52.dp else 60.dp)
                    .background(
                        if (translucent) surfaceColor.copy(alpha = 0.62f) else surfaceColor,
                    )
                    .padding(if (floating) 4.dp else 0.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(3) { index ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .background(
                                if (index == 0 && floating) {
                                    accentColor.copy(alpha = 0.16f)
                                } else {
                                    Color.Transparent
                                },
                            )
                            .padding(vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (index == 0) accentColor else contentColor.copy(alpha = 0.38f),
                                ),
                        )
                        Spacer(
                            modifier = Modifier
                                .width(18.dp)
                                .height(3.dp)
                                .clip(CircleShape)
                                .background(
                                    if (index == 0) accentColor else contentColor.copy(alpha = 0.32f),
                                ),
                        )
                    }
                }
            }
        }
    }
}
