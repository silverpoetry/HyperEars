package dev.hyperears.settings

enum class BatterySpeechMode(val title: String) {
    OFF("关闭"), ON("开启"), SCREEN_OFF("屏幕关闭时"),
    ;
    companion object {
        fun fromStoredValue(value: String?) = entries.firstOrNull { it.name == value } ?: OFF
    }
}
enum class BatterySpeechVolume(val percent: Int, val title: String) {
    P25(25,"25%"), P50(50,"50%"), P75(75,"75%"), P100(100,"100%"),
    ;
    companion object {
        fun fromStoredValue(value: String?) = entries.firstOrNull { it.name == value } ?: P50
    }
}
