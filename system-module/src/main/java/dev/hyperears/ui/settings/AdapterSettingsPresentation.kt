package dev.hyperears.ui.settings

import dev.hyperears.integration.EarbudAdapterDescriptor
import dev.hyperears.integration.EarbudAdapterGroup
import dev.hyperears.integration.EarbudAdapterKind
import dev.hyperears.settings.ModuleSettings

/** Shared, renderer-independent presentation model for adapter settings. */
internal data class AdapterGroupPresentation(
    val group: EarbudAdapterGroup,
    val adapterIds: Set<String>,
    val sections: List<AdapterSectionPresentation>,
) {
    fun enabledCount(disabledAdapterIds: Set<String>): Int =
        adapterIds.count { it !in disabledAdapterIds }
}

internal data class AdapterSectionPresentation(
    val kind: EarbudAdapterKind,
    val adapters: List<EarbudAdapterDescriptor>,
)

internal fun List<EarbudAdapterGroup>.toAdapterGroupPresentations(): List<AdapterGroupPresentation> =
    map { group ->
        AdapterGroupPresentation(
            group = group,
            adapterIds = group.adapters.mapTo(linkedSetOf(), EarbudAdapterDescriptor::id),
            sections = EarbudAdapterKind.entries.mapNotNull { kind ->
                group.adapters
                    .filter { it.kind == kind }
                    .takeIf(List<EarbudAdapterDescriptor>::isNotEmpty)
                    ?.let { adapters -> AdapterSectionPresentation(kind, adapters) }
            },
        )
    }

internal fun ModuleSettings.withAdapterGroupEnabled(
    group: AdapterGroupPresentation,
    enabled: Boolean,
): ModuleSettings = copy(
    disabledAdapterIds = disabledAdapterIds.withEnabledState(group.adapterIds, enabled),
)

internal fun ModuleSettings.withAdapterEnabled(
    adapterId: String,
    enabled: Boolean,
): ModuleSettings = copy(
    disabledAdapterIds = disabledAdapterIds.withEnabledState(setOf(adapterId), enabled),
)

private fun Set<String>.withEnabledState(
    adapterIds: Set<String>,
    enabled: Boolean,
): Set<String> = if (enabled) this - adapterIds else this + adapterIds
