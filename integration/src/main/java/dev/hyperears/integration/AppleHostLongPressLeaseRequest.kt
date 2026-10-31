package dev.hyperears.integration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Internal host executor ownership; the public UI continues to select per-bud actions. */
@Serializable
@SerialName("apple.host_long_press_lease")
data class AppleHostLongPressLeaseRequest(val leaseId: String, val acquire: Boolean) : ControlRequest

internal class AppleHostLongPressWireLease {
    private var owner: String? = null
    private var saved: List<Int>? = null
    fun change(request: AppleHostLongPressLeaseRequest, reported: List<Int>?): List<Int>? {
        if (request.leaseId.isBlank() || request.leaseId.length > 128 || reported?.size != 4) return null
        if (request.acquire) {
            if (owner != null || reported[0] and 8 != 0) return null
            owner = request.leaseId
            saved = reported.toList()
            return reported.toMutableList().also { it[0] = it[0] or 8 }
        }
        if (owner != request.leaseId) return null
        val original = saved ?: return null
        clear()
        // Restore only our routing bit; preserve independently changed fields and routing bits.
        return reported.toMutableList().also { it[0] = (it[0] and 8.inv()) or (original[0] and 8) }
    }
    fun clear() { owner = null; saved = null }
}
