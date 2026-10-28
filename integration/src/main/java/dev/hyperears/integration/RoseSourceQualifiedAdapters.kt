package dev.hyperears.integration

import dev.hyperears.protocol.rose.RoseManufacturerIdentityCodec
import dev.hyperears.protocol.rose.RoseRsV2IdentityCodec.Model

/** Immutable source dialect refinement; names and stale/ambiguous manufacturer data never switch it. */
object RoseSourceQualifiedAdapters {
    fun qualify(adapter: EarbudAdapter, identity: EarbudIdentity): EarbudAdapter {
        if(!identity.standardHeadset || identity.nativeSystemEarbud || identity.manufacturerData.isEmpty()) return adapter
        val id=RoseManufacturerIdentityCodec.modelId(identity.manufacturerData) ?: return adapter
        if(id != adapter.id || (adapter as? RoseBudsFeelProtocolFamilyAdapter)?.sourceProfile != null) return adapter
        return when(adapter) {
            is RoseBudsFeelMk2Adapter -> RoseBudsFeelMk2Adapter(sourceQualified=true)
            is RoseEarfeelI7Adapter -> RoseEarfeelI7Adapter(sourceQualified=true)
            is RoseCeramicsUAdapter -> RoseCeramicsUAdapter(sourceQualified=true)
            is FurinaEndlessAdapter -> FurinaEndlessAdapter(sourceQualified=true)
            else -> adapter
        }
    }
    fun needsDiscovery(adapter: EarbudAdapter): Boolean =
        adapter is RoseBudsFeelProtocolFamilyAdapter && adapter.sourceProfile == null &&
            Model.entries.any { it.existingAdapterId == adapter.id }
}
