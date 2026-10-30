package dev.hyperears.settings

import org.junit.Assert.*
import org.junit.Test

class ForcedAdapterSettingsTest {
    @Test
    fun defaultsStayAutomaticAndSelectionsAreScopedToOneAddress() {
        val original = ModuleSettings()
        assertTrue(original.forcedAdapterIds.isEmpty())
        val first = original.withForcedAdapter("aa:bb:cc:dd:ee:01", "oppo-enco-family")
        val both = first.withForcedAdapter("AA:BB:CC:DD:EE:02", "sony-family")
        assertEquals("oppo-enco-family", both.forcedAdapterIds["AA:BB:CC:DD:EE:01"])
        assertEquals(mapOf("AA:BB:CC:DD:EE:02" to "sony-family"),
            both.withForcedAdapter("AA:BB:CC:DD:EE:01", null).forcedAdapterIds)
        assertEquals(both.forcedAdapterIds, ForcedAdapterSettings.decode(ForcedAdapterSettings.encode(both.forcedAdapterIds)))
    }

    @Test
    fun malformedAndConflictingEntriesAreIgnoredWithoutChoosingAPrivateProtocol() {
        assertEquals(emptyMap<String, String>(), ForcedAdapterSettings.decode(setOf(
            "not-an-address=oppo-enco-family", "AA:BB:CC:DD:EE:01=", "AA:BB:CC:DD:EE:02=has spaces",
            "AA:BB:CC:DD:EE:03=first", "aa:bb:cc:dd:ee:03=second", "missing-separator",
        )))
    }
}
