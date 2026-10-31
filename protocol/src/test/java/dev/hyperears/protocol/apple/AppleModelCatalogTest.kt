package dev.hyperears.protocol.apple

import org.junit.Assert.*
import org.junit.Test

class AppleModelCatalogTest {
    @Test fun inventoryNamespacesNeverResolveCloneCollisionsAsAppleIdentity() {
        val originals=AppleModelCatalog.models.filter { it.namespace == AppleModelCatalog.Namespace.APPLE_AACP }
        val clones=AppleModelCatalog.models.filter { it.namespace == AppleModelCatalog.Namespace.CLONE_HQB }
        assertEquals(31,originals.size); assertEquals(6,clones.size)
        clones.forEach { clone ->
            assertEquals(clone,AppleModelCatalog.byIndex(AppleModelCatalog.Namespace.CLONE_HQB,clone.modelIndex))
            assertNotEquals(clone,AppleModelCatalog.byIndex(AppleModelCatalog.Namespace.APPLE_AACP,clone.modelIndex))
        }
        assertNull(AppleModelCatalog.byIndex(AppleModelCatalog.Namespace.APPLE_AACP,65535))
        assertNull(AppleModelCatalog.byModelIdentifier("AirPods Pro"))
        assertNull(AppleModelCatalog.byModelIdentifier("A344"))
        assertEquals("AIRPODS5_WIRELESS",AppleModelCatalog.byModelIdentifier("A3441")!!.key)
    }
}
