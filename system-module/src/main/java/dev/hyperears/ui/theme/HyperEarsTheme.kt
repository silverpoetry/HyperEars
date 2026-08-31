package dev.hyperears.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@Composable
fun HyperEarsTheme(
    preferences: UiPreferences,
    content: @Composable () -> Unit,
) {
    val preservedContent = remember {
        movableContentOf<@Composable () -> Unit> { targetContent ->
            targetContent()
        }
    }
    val darkTheme = preferences.themeMode.resolveDark(isSystemInDarkTheme())
    val context = LocalContext.current

    when (preferences.style) {
        UiStyle.MATERIAL3 -> {
            val colors = if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
            MaterialTheme(colorScheme = colors) {
                CompositionLocalProvider(LocalResolvedDarkTheme provides darkTheme) {
                    preservedContent(content)
                }
            }
        }

        UiStyle.MIUIX -> {
            val colorSchemeMode = when (preferences.themeMode) {
                UiThemeMode.SYSTEM -> ColorSchemeMode.System
                UiThemeMode.LIGHT -> ColorSchemeMode.Light
                UiThemeMode.DARK -> ColorSchemeMode.Dark
            }
            val controller = remember(colorSchemeMode, darkTheme) {
                ThemeController(
                    colorSchemeMode = colorSchemeMode,
                    isDark = darkTheme,
                )
            }
            MiuixTheme(controller = controller) {
                CompositionLocalProvider(LocalResolvedDarkTheme provides darkTheme) {
                    preservedContent(content)
                }
            }
        }
    }
}

private val LocalResolvedDarkTheme = staticCompositionLocalOf { false }

@Composable
@ReadOnlyComposable
internal fun isHyperEarsDarkTheme(): Boolean = LocalResolvedDarkTheme.current
