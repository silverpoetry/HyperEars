package dev.hyperears.ui.settings

import dev.hyperears.integration.EarbudAdapterDescriptor
import dev.hyperears.integration.EarbudAdapterGroup
import dev.hyperears.integration.EarbudAdapterKind
import dev.hyperears.settings.ModuleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdapterSettingsPresentationTest {
    private val modelAdapter = EarbudAdapterDescriptor(
        id = "vendor-model",
        displayName = "Vendor Model",
        kind = EarbudAdapterKind.MODEL,
    )
    private val familyAdapter = EarbudAdapterDescriptor(
        id = "vendor-family",
        displayName = "Vendor Family",
        kind = EarbudAdapterKind.FAMILY,
    )
    private val presentation = listOf(
        EarbudAdapterGroup(
            id = "vendor",
            displayName = "Vendor",
            adapters = listOf(modelAdapter, familyAdapter),
        ),
    ).toAdapterGroupPresentations().single()

    @Test
    fun presentationPreservesRegistryOrderAndGroupsAdaptersByKind() {
        assertEquals(setOf("vendor-model", "vendor-family"), presentation.adapterIds)
        assertEquals(
            listOf(EarbudAdapterKind.MODEL, EarbudAdapterKind.FAMILY),
            presentation.sections.map(AdapterSectionPresentation::kind),
        )
        assertEquals(2, presentation.enabledCount(emptySet()))
        assertEquals(1, presentation.enabledCount(setOf("vendor-model")))
    }

    @Test
    fun groupAndIndividualUpdatesShareTheSameStateTransition() {
        val disabledGroup = ModuleSettings().withAdapterGroupEnabled(presentation, enabled = false)
        assertTrue(presentation.adapterIds.all { it in disabledGroup.disabledAdapterIds })

        val partiallyEnabled = disabledGroup.withAdapterEnabled(modelAdapter.id, enabled = true)
        assertFalse(modelAdapter.id in partiallyEnabled.disabledAdapterIds)
        assertTrue(familyAdapter.id in partiallyEnabled.disabledAdapterIds)

        val enabledGroup = partiallyEnabled.withAdapterGroupEnabled(presentation, enabled = true)
        assertTrue(presentation.adapterIds.none { it in enabledGroup.disabledAdapterIds })
    }
}
