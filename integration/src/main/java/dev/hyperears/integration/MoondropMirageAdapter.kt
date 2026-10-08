package dev.hyperears.integration

/**
 * MIRAGE shares the Pudding FF/Jieli protocol. The contributor verified component battery and
 * basic ANC/off/ambient; adaptive ANC bytes remain unverified. Each instance owns its state.
 */
class MoondropMirageAdapter : MoondropJieliAdapter("moondrop-mirage-spp") {
    override val id: String = ID
    override val displayName: String = "MOONDROP MIRAGE"

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!identity.standardHeadset || identity.nativeSystemEarbud) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        return ("moondrop" in name || "水月雨" in name) && "mirage" in name
    }

    companion object {
        const val ID = "moondrop-mirage"
        const val STANDARD_SPP_UUID = MoondropJieliAdapter.STANDARD_SPP_UUID
        internal const val INITIAL_MODE_QUERY_DELAY_MS = MoondropJieliAdapter.INITIAL_MODE_QUERY_DELAY_MS
        internal val MODE_CONFIRMATION_DELAYS_MS = MoondropJieliAdapter.MODE_CONFIRMATION_DELAYS_MS
    }
}
