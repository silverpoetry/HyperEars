package dev.hyperears.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class UiStyleTest {
    @Test
    fun miuixIsTheDefaultWhenNoPreferenceExists() {
        assertEquals(UiStyle.MIUIX, UiStyle.fromStoredValue(null))
        assertEquals(UiStyle.MIUIX, UiStyle.fromStoredValue("unknown"))
    }

    @Test
    fun restoresEveryKnownStyle() {
        UiStyle.entries.forEach { style ->
            assertEquals(style, UiStyle.fromStoredValue(style.name))
        }
    }
}
