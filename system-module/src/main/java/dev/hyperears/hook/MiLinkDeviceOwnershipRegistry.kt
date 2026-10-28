package dev.hyperears.hook

import java.util.Locale
import dev.hyperears.presentation.HeadsetConnection

/**
 * Process-local ownership decided at MiLink's native headset-admission boundary.
 *
 * MiLinkServiceHook observes exactly one native boundary, so every positive original result is
 * authoritative. System ownership is sticky and may promote a provisional HyperEars owner if the
 * platform later recognizes the same address after its own metadata becomes available.
 * Explicit Apple admission scopes limit that stickiness to the authenticated physical connection;
 * unscoped/non-Apple devices retain the original process-lifetime policy.
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

    data class ConnectionScope(val producer: Any, val connection: HeadsetConnection)
    private val lock = Any()
    private val connectionScopes = mutableMapOf<String, ConnectionScope>()
    private val retiredConnectionScopes = mutableMapOf<String, MutableSet<ConnectionScope>>()
    private val owners = mutableMapOf<String, Owner>()
    private val deliveredSystemClaims = mutableMapOf<String, String>()

    fun acceptsConnectionScope(address: String, scope: ConnectionScope): Boolean = synchronized(lock) {
        val key = normalizeAddress(address)
        val current = connectionScopes[key]
        scope !in retiredConnectionScopes[key].orEmpty() &&
            (current == null || current == scope ||
                scope.connection.connectedAtElapsedMs >= current.connection.connectedAtElapsedMs)
    }

    /** Logical adapter/session replacement within this physical scope never clears native-positive. */
    fun bindConnectionScope(address: String, scope: ConnectionScope): Boolean = synchronized(lock) {
        val key = normalizeAddress(address)
        if (!acceptsConnectionScope(address, scope) || connectionScopes[key] == scope) return false
        connectionScopes.put(key, scope)?.let { retiredConnectionScopes.getOrPut(key) { mutableSetOf() }.add(it) }
        owners.remove(key)
        deliveredSystemClaims.remove(key)
        true
    }

    /** Accept state and bind its physical scope without exposing new state under an old scope. */
    fun bindAcceptedConnectionScope(address: String, scope: ConnectionScope,
        acceptState: () -> Boolean): Boolean? = synchronized(lock) {
        if (!acceptsConnectionScope(address, scope) || !acceptState()) return null
        bindConnectionScope(address, scope)
    }

    /** Original platform calls run outside this lock; only their scoped result and effects commit here. */
    fun <T> withCurrentConnectionScope(address: String, expected: ConnectionScope?,
        commit: () -> T): T? = synchronized(lock) {
        if (connectionScopes[normalizeAddress(address)] != expected) return null
        commit()
    }

    fun claimSharedSystemOwnership(address: String, scope: ConnectionScope?): Boolean = synchronized(lock) {
        val current = connectionScopes[normalizeAddress(address)]
        if (current != null && current != scope) return false
        if (current == null && scope != null) {
            if (!acceptsConnectionScope(address, scope)) return false
            bindConnectionScope(address, scope)
        }
        claimSystemOwnership(address)
    }

    fun connectionScope(address: String): ConnectionScope? = synchronized(lock) {
        connectionScopes[normalizeAddress(address)]
    }

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
        connectionScopes.clear()
        retiredConnectionScopes.clear()
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
