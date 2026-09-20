package dev.hyperears.integration

import dev.hyperears.protocol.moondrop.MoondropModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps the offline snapshot table and the runtime adapters from drifting apart: catalog
 * aliases must stay acceptable by the corresponding exact-model Adapter, and catalog UUIDs
 * must remain unique. This is a reconciliation test, not a support promise — the catalog
 * never feeds device matching itself.
 */
class MoondropModelCatalogTest {
    @Test
    fun catalogUuidsAreUnique() {
        val knownUuids = MoondropModelCatalog.models.mapNotNull { it.uuid }
        assertEquals(knownUuids.size, knownUuids.distinct().size)
    }

    @Test
    fun everyCatalogAliasIsAcceptedByItsAdapter() {
        MoondropModelCatalog.models.forEach { entry ->
            val adapter = adapterFor(entry)
            entry.normalizedAliases.forEach { alias ->
                val identity = EarbudIdentity(deviceName = alias, standardHeadset = true)
                assertTrue(
                    "alias '$alias' of ${entry.displayName} is rejected by ${adapter.id}",
                    adapter.matches(identity),
                )
            }
        }
    }

    @Test
    fun snapshotFactsArePinned() {
        assertEquals("2026-09-16", MoondropModelCatalog.SNAPSHOT_FETCHED_AT)
        assertEquals(
            "https://cdn-service.moondroplab.tech/api/v1/products/all",
            MoondropModelCatalog.SNAPSHOT_SOURCE_URL,
        )
        assertEquals(
            "e9aa2b61-74d5-451f-995d-254d3a9e6666",
            MoondropModelCatalog.MIRAGE.uuid,
        )
        assertEquals(
            "291a21ef-bca1-4cf1-baf8-151c457bf082",
            MoondropModelCatalog.PUDDING.uuid,
        )
        assertNotNull(MoondropModelCatalog.findByUuid(MoondropModelCatalog.MIRAGE.uuid!!))
        assertSame(
            MoondropModelCatalog.MIRAGE,
            MoondropModelCatalog.findByUuid("e9aa2b61-74d5-451f-995d-254d3a9e6666"),
        )
        assertEquals(MoondropModelCatalog.MoondropPlane.FF_JIELI, MoondropModelCatalog.MIRAGE.plane)
        assertEquals(
            MoondropModelCatalog.MoondropPlane.FF_BLUETRUM,
            MoondropModelCatalog.ROBIN.plane,
        )
    }

    private fun adapterFor(entry: MoondropModelCatalog.ModelEntry): MoondropEarbudAdapter =
        when (entry) {
            MoondropModelCatalog.MIRAGE -> MoondropMirageAdapter()
            MoondropModelCatalog.PUDDING -> MoondropPuddingAdapter()
            MoondropModelCatalog.ROBIN -> MoondropRobinAdapter()
            else -> error("no adapter mapping for catalog row ${entry.displayName}")
        }
}
