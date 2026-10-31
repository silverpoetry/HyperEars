package dev.hyperears.hook

import dev.hyperears.integration.AdapterSnapshot
import dev.hyperears.integration.HeadsetFormFactor
import dev.hyperears.integration.MiLinkCardPresentationId

/**
 * Maps semantic headset form factors onto stock MiLink registry entries.
 *
 * These IDs are compatibility carriers, not model identity. MiLink can therefore run its native
 * admission, duplicate suppression and cross-process type reconstruction without any registry or
 * obfuscated classifier hooks.
 */
internal object MiLinkCarrierIdentity {
    const val TWS_DEVICE_ID = "01010607"

    /**
     * Xiaomi O70C family entry classified as native headset type 7 on MiLink 17.2.x.
     *
     * Keep carrier verification in compatibility tests when adding another supported MiLink
     * release; adapters must never depend on the Xiaomi model represented by this value.
     */
    const val HEADPHONES_DEVICE_ID = "01013A04"

    fun deviceId(adapter: AdapterSnapshot): String = when (adapter.formFactor) {
        HeadsetFormFactor.TWS -> TWS_DEVICE_ID
        HeadsetFormFactor.GLASSES, HeadsetFormFactor.HEADPHONES -> HEADPHONES_DEVICE_ID
    }
}

/**
 * Optional HyperEars presentation metadata carried by MiLink's extensible service-properties
 * bundle. Generic headset behavior never depends on these keys.
 */
internal object MiLinkPresentationContract {
    const val SCHEMA_KEY = "dev.hyperears.presentation.schema"
    const val PRESENTATION_KEY = "dev.hyperears.presentation.id"
    const val SCHEMA_VERSION = 1

    fun decode(
        schemaVersion: Int,
        presentationId: String?,
    ): MiLinkCardPresentationId? =
        presentationId
            ?.takeIf { schemaVersion == SCHEMA_VERSION && it.isNotBlank() }
            ?.let(::MiLinkCardPresentationId)
}
