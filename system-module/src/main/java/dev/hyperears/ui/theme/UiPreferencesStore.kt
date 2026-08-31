package dev.hyperears.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns app-only appearance preferences.
 *
 * These values are intentionally separate from [dev.hyperears.settings.ModuleSettings]: they are
 * consumed only by the HyperEars application process and do not need to cross an Xposed process
 * boundary. Keeping the legacy preference file and style key preserves existing installations.
 */
class UiPreferencesStore(context: Context) {
    private val sharedPreferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val mutableState = MutableStateFlow(sharedPreferences.readUiPreferences())

    val state: StateFlow<UiPreferences> = mutableState.asStateFlow()

    fun update(preferences: UiPreferences) {
        val updated = preferences.normalized()
        if (updated == mutableState.value) return
        sharedPreferences.edit {
            putString(KEY_STYLE, updated.style.name)
            putString(KEY_THEME_MODE, updated.themeMode.name)
            putBoolean(KEY_NAVIGATION_BLUR, updated.navigationBlur)
            putBoolean(KEY_FLOATING_NAVIGATION_BAR, updated.floatingNavigationBar)
            putFloat(KEY_INTERFACE_SCALE, updated.interfaceScale)
        }
        mutableState.value = updated
    }

    private fun SharedPreferences.readUiPreferences(): UiPreferences = UiPreferences(
        style = UiStyle.fromStoredValue(getString(KEY_STYLE, null)),
        themeMode = UiThemeMode.fromStoredValue(getString(KEY_THEME_MODE, null)),
        navigationBlur = getBoolean(KEY_NAVIGATION_BLUR, false),
        floatingNavigationBar = getBoolean(KEY_FLOATING_NAVIGATION_BAR, false),
        interfaceScale = getFloat(
            KEY_INTERFACE_SCALE,
            UiPreferences.DEFAULT_INTERFACE_SCALE,
        ),
    ).normalized()

    private companion object {
        const val PREFERENCES_NAME = "hyperears_ui"
        const val KEY_STYLE = "style"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_NAVIGATION_BLUR = "navigation_blur"
        const val KEY_FLOATING_NAVIGATION_BAR = "floating_navigation_bar"
        const val KEY_INTERFACE_SCALE = "interface_scale"
    }
}
