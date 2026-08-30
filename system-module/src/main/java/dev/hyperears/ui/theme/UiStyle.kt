package dev.hyperears.ui.theme

enum class UiStyle(
    val displayName: String,
) {
    MATERIAL3("Material 3"),
    MIUIX("Miuix"),
    ;

    companion object {
        fun fromStoredValue(value: String?): UiStyle =
            entries.firstOrNull { it.name == value } ?: MIUIX
    }
}
