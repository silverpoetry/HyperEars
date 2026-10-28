package dev.hyperears.settings

/** Stable optional percentages; OFF never mutates an existing route. */
enum class ConnectionVolumePreset(val percent: Int?, val title: String) {
    OFF(null, "关闭"), MUTE(0, "0%（系统最低音量）"), P10(10, "10%"), P20(20, "20%"),
    P30(30, "30%"), P40(40, "40%"), P50(50, "50%"), P60(60, "60%"),
    P70(70, "70%"), P80(80, "80%"), P90(90, "90%"), P100(100, "100%"),
    ;
    companion object {
        const val TITLE = "连接媒体音量"
        const val SUMMARY = "下次连接时，10 秒内仅对当前耳机的媒体输出应用一次。手动调音量会取消；断开不恢复。"
        fun fromStoredValue(value: String?): ConnectionVolumePreset = entries.firstOrNull { it.name == value } ?: OFF
    }
}
