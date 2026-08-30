package dev.hyperears.ui.theme

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UiStyleStore(context: Context) {
    private val preferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val mutableStyle = MutableStateFlow(
        UiStyle.fromStoredValue(preferences.getString(KEY_STYLE, null)),
    )

    val style: StateFlow<UiStyle> = mutableStyle.asStateFlow()

    fun update(style: UiStyle) {
        if (mutableStyle.value == style) return
        preferences.edit { putString(KEY_STYLE, style.name) }
        mutableStyle.value = style
    }

    private companion object {
        const val PREFERENCES_NAME = "hyperears_ui"
        const val KEY_STYLE = "style"
    }
}
