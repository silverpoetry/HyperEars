package dev.hyperears.hook

import java.util.Locale

/**
 * Process-local ownership decided at MiLink's native headset-admission boundary.
 *
 * MiLinkServiceHook observes exactly one native boundary, so every positive original result is
 * authoritative. System ownership is sticky and may promote a provisional HyperEars owner if the
 * platform later recognizes the same address after its own metadata becomes available.
 */
internal class MiLinkDeviceOwnershipRegistry {
    enum class Owner {
        UNKNOWN,
        HYPEREARS,
        SYSTEM,
    }

    data class Decision(
        val owner: Owner,
        val systemOwnershipNewlyClaimed: Boolean,
        val hyperEarsOwnershipNewlyClaimed: Boolean,
    )

    private val lock = Any()
    private val owners = mutableMapOf<String, Owner>()
    private val deliveredSystemClaims = mutableMapOf<String, String>()

    fun observeNativeAdmission(
        address: String,
        originalResult: Any?,
        hyperEarsCandidateAvailable: Boolean,
    ): Decision = synchronized(lock) {
        val key = normalizeAddress(address)
        val previous = owners[key] ?: Owner.UNKNOWN
        val next = when {
            previous == Owner.SYSTEM -> Owner.SYSTEM
            isNativeAdmissionAccepted(originalResult) -> Owner.SYSTEM
            previous == Owner.HYPEREARS -> Owner.HYPEREARS
            hyperEarsCandidateAvailable && isNativeAdmissionRejected(originalResult) -> Owner.HYPEREARS
            else -> previous
        }
        owners[key] = next
        Decision(
            owner = next,
            systemOwnershipNewlyClaimed =
                previous != Owner.SYSTEM && next == Owner.SYSTEM,
            hyperEarsOwnershipNewlyClaimed =
                previous != Owner.HYPEREARS && next == Owner.HYPEREARS,
        )
    }

    fun owner(address: String): Owner = synchronized(lock) {
        owners[normalizeAddress(address)] ?: Owner.UNKNOWN
    }

    fun isHyperEarsOwned(address: String): Boolean = owner(address) == Owner.HYPEREARS

    fun isSystemOwned(address: String): Boolean = owner(address) == Owner.SYSTEM

    fun claimSystemOwnership(address: String): Boolean = synchronized(lock) {
        val key = normalizeAddress(address)
        val previous = owners[key] ?: Owner.UNKNOWN
        owners[key] = Owner.SYSTEM
        previous != Owner.SYSTEM
    }

    fun clear() = synchronized(lock) {
        owners.clear()
        deliveredSystemClaims.clear()
    }

    /** Initial native claims may precede a session; successful session deliveries are deduplicated. */
    fun deliverSystemOwnershipClaim(
        address: String,
        sessionToken: String?,
        deliver: () -> Boolean,
    ) = synchronized(lock) {
        val key = normalizeAddress(address)
        if (owners[key] != Owner.SYSTEM) return@synchronized
        if (sessionToken != null && deliveredSystemClaims[key] == sessionToken) return@synchronized
        if (deliver() && sessionToken != null) deliveredSystemClaims[key] = sessionToken
    }

    private fun normalizeAddress(address: String): String = address.uppercase(Locale.ROOT)

    companion object {
        /** Supports the Boolean and numeric return shapes used by different MiLink releases. */
        fun isNativeAdmissionAccepted(result: Any?): Boolean = when (result) {
            is Boolean -> result
            is Number -> result.toLong() == 1L
            else -> false
        }

        fun isNativeAdmissionRejected(result: Any?): Boolean = when (result) {
            is Boolean -> !result
            is Number -> result.toLong() == 0L
            else -> false
        }
    }
}
