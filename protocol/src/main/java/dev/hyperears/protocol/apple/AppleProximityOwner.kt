package dev.hyperears.protocol.apple

/** In-memory only. All calls serialized by the host; revoke destroys keys and invalidates late callbacks. */
class AppleProximityOwner(private val pairedAddress: String) {
    private var generation = 0L
    private var admitted = false
    private var irk: ByteArray? = null
    private var enc: ByteArray? = null
    var lid = AppleProximityLidSession()
        private set
    fun admit(): Long {
        revoke()
        admitted = true
        return generation
    }
    fun revoke() {
        generation++
        admitted = false
        irk?.fill(0); enc?.fill(0)
        irk = null; enc = null
        lid = AppleProximityLidSession()
    }
    fun acceptKeys(token: Long, keys: AppleProximityKeys): Boolean {
        try {
            if (!admitted || token != generation) return false
            keys.copyKey(1)?.let { irk?.fill(0); irk = it }
            keys.copyKey(4)?.let { enc?.fill(0); enc = it }
            return true
        } finally { keys.close() }
    }
    fun observe(token: Long, address: String, message: ByteArray, capturedAt: Long, now: Long): AppleProximityReport? {
        if (!admitted || token != generation) return null
        if (!pairedAddress.equals(address, true) && irk?.let { AppleProximityCodec.matchesIdentity(address, it) } != true) return null
        val report = AppleProximityCodec.decode(message, enc) ?: return null
        return report.takeIf { lid.observe(it.lidOpen, capturedAt, now) }
    }
}
