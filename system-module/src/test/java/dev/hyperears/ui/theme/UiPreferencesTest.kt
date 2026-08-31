package dev.hyperears.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiPreferencesTest {
    @Test
    fun miuixIsTheDefaultWhenNoStylePreferenceExists() {
        assertEquals(UiStyle.MIUIX, UiStyle.fromStoredValue(null))
        assertEquals(UiStyle.MIUIX, UiStyle.fromStoredValue("unknown"))
        assertEquals(UiStyle.MIUIX, UiPreferences().style)
    }

    @Test
    fun restoresEveryKnownStyle() {
        UiStyle.entries.forEach { style ->
            assertEquals(style, UiStyle.fromStoredValue(style.name))
        }
    }

    @Test
    fun themeModeFallsBackToSystemAndResolvesExplicitModes() {
        assertEquals(UiThemeMode.SYSTEM, UiThemeMode.fromStoredValue(null))
        assertEquals(UiThemeMode.SYSTEM, UiThemeMode.fromStoredValue("unknown"))
        assertTrue(UiThemeMode.SYSTEM.resolveDark(systemDark = true))
        assertFalse(UiThemeMode.LIGHT.resolveDark(systemDark = true))
        assertTrue(UiThemeMode.DARK.resolveDark(systemDark = false))
    }

    @Test
    fun normalizationBoundsScaleWithoutDiscardingMiuixEffects() {
        assertEquals(
            UiPreferences.MIN_INTERFACE_SCALE,
            UiPreferences(interfaceScale = 0.1f).normalized().interfaceScale,
        )
        val normalized = UiPreferences(
            style = UiStyle.MATERIAL3,
            navigationBlur = true,
            floatingNavigationBar = true,
            interfaceScale = 3f,
        ).normalized()
        assertEquals(UiPreferences.MAX_INTERFACE_SCALE, normalized.interfaceScale)
        assertTrue(normalized.navigationBlur)
        assertTrue(normalized.floatingNavigationBar)
    }
}
