package dev.hyperears.integration

/**
 * Classic OPO candidate for numbered OnePlus Buds / Buds Pro generations.
 *
 * Buds 4 captures confirm the existing OPPO reads and standard three-state writes. Public Buds
 * Pro 3 / Buds 4 implementations also share the RFCOMM endpoint and this core mapping. Keep
 * separate brand/catalog identities while inheriting the same session, codec and controller apps.
 * Nord, Z, Ace and other product lines need their own transport/mapping evidence before inclusion.
 */
open class OnePlusEarbudAdapter : OpoProtocolAdapter() {
    override val id: String = ID
    override val displayName: String = "OnePlus Buds / Buds Pro"
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH

    override fun matches(identity: EarbudIdentity): Boolean =
        identity.standardHeadset && !identity.nativeSystemEarbud &&
            FAMILY_NAME.matches(normalizeDeviceName(identity.deviceName.orEmpty()))

    companion object {
        const val ID = "oneplus-buds-opo-family"
        private val FAMILY_NAME = Regex("(?:oneplus|一加)buds(?:pro(?:[1-9][0-9]?)?|[1-9][0-9]?(?:pro)?)?")
    }
}

/** The issue #94 device uses the shared OPO configuration; exact identity remains selectable. */
class OnePlusBuds4Adapter : OnePlusEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "OnePlus Buds 4"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) && normalizeDeviceName(identity.deviceName.orEmpty()) in EXACT_NAMES

    companion object {
        const val ID = "oneplus-buds-4"
        private val EXACT_NAMES = setOf("oneplusbuds4", "一加buds4")
    }
}
