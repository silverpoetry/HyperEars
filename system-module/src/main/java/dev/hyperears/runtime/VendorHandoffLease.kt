package dev.hyperears.runtime

import dev.hyperears.integration.ControlAppCatalog
import dev.hyperears.integration.ControlAppSpec

/** Session-owned override. A bounded launch grace ends when presence is first registered. */
internal class VendorHandoffLease {
    data class Lease(val packageName: String, val id: String, val presenceSeen: Boolean)
    var current: Lease? = null
        private set

    fun begin(packageName: String, id: String, activePackages: Set<String>) {
        current = Lease(packageName, id, packageName in activePackages)
    }

    fun presence(activePackages: Set<String>) {
        val lease = current ?: return
        current = when {
            lease.packageName in activePackages -> lease.copy(presenceSeen = true)
            lease.presenceSeen -> null
            else -> lease
        }
    }

    fun expire(id: String): Boolean {
        val lease = current ?: return false
        return !lease.presenceSeen && end(id)
    }

    fun end(id: String): Boolean {
        if (current?.id != id) return false
        current = null
        return true
    }

    fun owner(candidates: List<ControlAppSpec>, activePackages: Set<String>, automaticYield: Boolean): ControlAppSpec? =
        candidates.firstOrNull { it.packageName == current?.packageName }
            ?: if (automaticYield) ControlAppCatalog.activeOwner(candidates, activePackages) else null

    fun clear() { current = null }
}
