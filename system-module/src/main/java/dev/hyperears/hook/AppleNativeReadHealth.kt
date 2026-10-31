package dev.hyperears.hook

/** Captures failures before the vendor's catch blocks can turn them into empty/false values. */
internal class AppleNativeReadHealth(val key: String) {
    private var read = false
    private var failed = false
    private var type: String? = null
    private var cloud: Int? = null
    private var builtin: Boolean? = null
    private fun original(call: () -> Any?, accept: (Any?) -> Unit): Any? = try {
        call().also(accept)
    } catch (error: Throwable) { failed = true; throw error }
    fun preference(call: () -> Any?): Any? = original(call) { read = true; type = it as? String }
    fun cloud(call: () -> Any?): Any? = original(call) { cloud = it as? Int }
    fun builtin(call: () -> Any?): Any? = original(call) { builtin = it as? Boolean }
    fun failed() { failed = true }
    fun declineHealthy(cloudState: Int, cloudAbsent: () -> Boolean): Boolean = runCatching {
        read && !failed && (type.isNullOrEmpty() ||
            ((cloud == 3 || (cloud == 1 && builtin == false)) &&
                (cloudState == 0 || (cloudState == -1 && cloudAbsent()))))
    }.getOrDefault(false)
}
