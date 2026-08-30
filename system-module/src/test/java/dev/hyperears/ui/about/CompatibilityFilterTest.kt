package dev.hyperears.ui.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityFilterTest {
    @Test
    fun blankQueryReturnsTheCatalogUnchanged() {
        assertEquals(supportBrands, filterSupportBrands("  "))
    }

    @Test
    fun brandQueryKeepsEveryMatchingBrandEntry() {
        val result = filterSupportBrands("Bose")

        assertEquals(listOf("Bose"), result.map(SupportBrand::name))
        assertEquals(
            supportBrands.first { it.name == "Bose" }.entries,
            result.single().entries,
        )
    }

    @Test
    fun multipleTermsFilterByModelAndCapability() {
        val result = filterSupportBrands("Sony 抗风噪")

        assertEquals(listOf("Sony"), result.map(SupportBrand::name))
        assertTrue(result.single().entries.isNotEmpty())
        assertTrue(result.single().entries.all { "抗风噪" in it.noiseControl })
    }

    @Test
    fun evidenceAndBatteryLabelsAreSearchable() {
        val result = filterSupportBrands("实机验证 左右耳电量")

        assertTrue(result.isNotEmpty())
        assertTrue(
            result.flatMap(SupportBrand::entries).all { entry ->
                entry.evidence == EvidenceLevel.VERIFIED &&
                    entry.battery == BatteryCapability.LEFT_RIGHT
            },
        )
    }

    @Test
    fun findsTheVerifiedFreeClip2BatteryOnlyEntry() {
        val result = filterSupportBrands("FreeClip 2 实机验证")

        assertEquals(listOf("华为"), result.map(SupportBrand::name))
        assertEquals(
            listOf("HUAWEI FreeClip 2"),
            result.single().entries.map(SupportEntry::name),
        )
        assertEquals("无", result.single().entries.single().noiseControl)
    }
}
