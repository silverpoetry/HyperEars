package dev.hyperears.firmware

/** One explicit check, tied to the displayed firmware and cancelled with its UI owner. */
internal class CoralFirmwareCheckFlow(
    private val work: (() -> Unit) -> Unit,
    private val deliver: (() -> Unit) -> Unit,
    private val check: (Int, String, CoralFirmwareCancellation) -> CoralFirmwareCheck,
) {
    private var current: CoralFirmwareCancellation? = null
    private var closed = false
    val busy: Boolean get() = current != null

    fun start(version: Int, language: String, stillCurrent: () -> Boolean,
        result: (Result<CoralFirmwareCheck>) -> Unit): Boolean {
        if (closed || busy || version !in 1..65535) return false
        val operation = CoralFirmwareCancellation()
        current = operation
        try {
            work {
                val outcome = runCatching { check(version, language, operation) }
                deliver {
                    if (!closed && current === operation) {
                        current = null
                        if (stillCurrent()) result(outcome)
                    }
                }
            }
        } catch (error: Exception) {
            current = null
            operation.cancel()
            result(Result.failure(error))
        }
        return true
    }

    fun close() {
        closed = true
        current?.cancel()
        current = null
    }
}
