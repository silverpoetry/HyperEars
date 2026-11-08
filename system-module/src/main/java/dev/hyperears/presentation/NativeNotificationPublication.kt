package dev.hyperears.presentation

/** Supported tagged publication boundaries. Untagged or foreign publishers are never hooked. */
internal fun isNativeDetailPublication(owner: String, name: String, parameters: List<String>): Boolean {
    if (owner != "android.app.NotificationManager") return false
    val common = listOf("java.lang.String", "int", "android.app.Notification")
    return when (name) {
        "notify" -> parameters == common
        "notifyAsUser" -> parameters == common + "android.os.UserHandle"
        else -> false
    }
}
