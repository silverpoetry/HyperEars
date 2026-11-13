package dev.hyperears.presentation

/** Main-thread-confined initialization outcome, not an event history or device-ownership cache. */
internal class NativeHostAvailability {
    private var environment = "initialization not completed"
    private var capabilities: Map<String, Result<Unit>> = emptyMap()

    fun complete(environment: String, capabilities: Map<String, Result<Unit>>) {
        this.environment = environment
        this.capabilities = capabilities.toMap()
    }

    /** No resolver retry or logging work occurs while diagnostics are disabled. */
    fun report(enabled: Boolean, reason: String, emit: (String, Throwable?) -> Unit) {
        if (!enabled) return
        emit("host status reason=$reason $environment", null)
        capabilities.forEach { (name, result) ->
            emit("host capability=$name ready=${result.isSuccess}", result.exceptionOrNull())
        }
    }
}

/** Gives a failed fingerprint a stable operation name without accepting absent/ambiguous matches. */
internal fun <T> List<T>.singleNativeContract(role: String): T {
    check(size == 1) { "Native contract '$role' expected one match, found $size" }
    return first()
}
