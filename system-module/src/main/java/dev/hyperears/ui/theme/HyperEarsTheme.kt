package dev.hyperears.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@Composable
fun HyperEarsTheme(
    style: UiStyle,
    content: @Composable () -> Unit,
) {
    val preservedContent = remember {
        movableContentOf<@Composable () -> Unit> { targetContent ->
            targetContent()
        }
    }
    val darkTheme = isSystemInDarkTheme()
    val context = LocalContext.current

    when (style) {
        UiStyle.MATERIAL3 -> {
            val colors = if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
            MaterialTheme(colorScheme = colors) {
                preservedContent(content)
            }
        }

        UiStyle.MIUIX -> {
            val controller = remember(darkTheme) {
                ThemeController(
                    colorSchemeMode = ColorSchemeMode.System,
                    isDark = darkTheme,
                )
            }
            MiuixTheme(controller = controller) {
                preservedContent(content)
            }
        }
    }
}
