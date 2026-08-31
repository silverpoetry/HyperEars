package dev.hyperears.ui.theme

enum class UiThemeMode(
    val displayName: String,
) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
    ;

    fun resolveDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromStoredValue(value: String?): UiThemeMode =
            entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

/**
 * App-process-only appearance preferences. Renderer-specific values remain persisted when another
 * renderer is selected so switching styles does not discard the user's previous Miuix setup.
 */
data class UiPreferences(
    val style: UiStyle = UiStyle.MIUIX,
    val themeMode: UiThemeMode = UiThemeMode.SYSTEM,
    val navigationBlur: Boolean = false,
    val floatingNavigationBar: Boolean = false,
    val interfaceScale: Float = DEFAULT_INTERFACE_SCALE,
) {
    fun normalized(): UiPreferences = copy(
        interfaceScale = interfaceScale.coerceIn(
            MIN_INTERFACE_SCALE,
            MAX_INTERFACE_SCALE,
        ),
    )

    companion object {
        const val MIN_INTERFACE_SCALE = 0.9f
        const val DEFAULT_INTERFACE_SCALE = 1.0f
        const val MAX_INTERFACE_SCALE = 1.1f
    }
}
