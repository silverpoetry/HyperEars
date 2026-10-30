package dev.hyperears.presentation

import dev.hyperears.integration.BatteryReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeIslandPresentationTest {

    // Fixture tree with the host's declared field names, so the manual serializer is testable on the JVM.
    private class TextFixture(private val text: String?, private val textColor: Int?,
        private val turnAnim: Boolean)

    private class IconFixture(private val iconResName: String?, private val iconType: Int?,
        private val iconFormat: String?, private val category: String?)

    private class AreaFixture(private val textParams: Any?, private val iconParams: Any?)

    private class ParamFixture(private val left: Any?, private val right: Any?)

    @Test
    fun missingBatteryReadsRenderNoText() {
        // "-" is the host's missing-reading marker and must become an absent text area (spec §4.1).
        // BatteryReading.available is derived from its percent, so only a null percent is "-".
        assertNull(islandBatteryText(BatteryReading(null, false)))
        assertNull(islandBatteryText(BatteryReading(null, true)))
        assertEquals("0%", islandBatteryText(BatteryReading(0, false)))
        assertEquals("85%", islandBatteryText(BatteryReading(85, true)))
    }

    @Test
    fun batteryColorUsesTheTwentyPercentThreshold() {
        val low = NativeIslandPresentationConstants.LOW_BATTERY_COLOR_RES
        val normal = NativeIslandPresentationConstants.NORMAL_BATTERY_COLOR_RES
        assertEquals(0x7f060070, low)
        assertEquals(0x7f060071, normal)
        assertEquals(low, islandTextColorRes(20))
        assertEquals(low, islandTextColorRes(0))
        assertEquals(normal, islandTextColorRes(21))
        assertEquals(normal, islandTextColorRes(100))
    }

    @Test
    fun textOnlyAreaKeepsTheHostShapeWithoutIconNames() {
        val area = islandArea("85%", 111)!!
        assertEquals(IslandTextParams("85%", 111, true), area.textParams)
        assertEquals(IslandIconParams(null, 0, null, null), area.iconParams)
        assertNull(islandArea(null, 111))
    }

    @Test
    fun paramJsonUsesHostFieldNamesAndOmitsNulls() {
        val left = islandArea("85%", 111)
        val right = islandArea("15%", 222)
        assertEquals("""{"left":{"textParams":{"text":"85%","textColor":111,"turnAnim":true},""" +
            """"iconParams":{"iconType":0}},"right":{"textParams":{"text":"15%","textColor":222,""" +
            """"turnAnim":true},"iconParams":{"iconType":0}}}""", islandParamJson(left, right))
        assertEquals("""{"left":{"textParams":{"text":"85%","textColor":111,"turnAnim":true},""" +
            """"iconParams":{"iconType":0}}}""", islandParamJson(left, null))
        assertNull(islandParamJson(null, null))
    }

    @Test
    fun paramJsonWalksConstructedHostObjects() {
        val param = ParamFixture(
            AreaFixture(TextFixture("85%", 111, true), IconFixture(null, 0, null, null)),
            null,
        )
        assertEquals("""{"left":{"textParams":{"text":"85%","textColor":111,"turnAnim":true},""" +
            """"iconParams":{"iconType":0}}}""", islandParamJson(param))
    }

    @Test
    fun unresolvedColorOmitsTheTextColorField() {
        // A color the module context cannot resolve must never ride out as a raw resource id: the
        // nullable host field keeps it absent and SystemUI applies its own default (spec §3).
        assertNull(islandArea(BatteryReading(85, true)) { null }?.textParams?.textColor)
        assertEquals("""{"left":{"textParams":{"text":"85%","turnAnim":true},""" +
            """"iconParams":{"iconType":0}}}""", islandParamJson(islandArea("85%", null), null))
    }

    @Test
    fun jsonStringEscapesControlAndQuoteCharacters() {
        assertEquals("\"85%\"", jsonString("85%"))
        assertEquals("\"a\\\"b\\\\c\"", jsonString("a\"b\\c"))
        assertEquals("\"a\\nb\"", jsonString("a\nb"))
        assertEquals("\"a\\u0001b\"", jsonString("a\u0001b"))
    }

    @Test
    fun bundleEntriesCarryTheFullHostKeyTable() {
        val entries = islandBundleEntries("JSON", 5000L)
        assertEquals(7, entries.size)
        val byKey = entries.associateBy { it.key }
        assertEquals(setOf("notifyId", "strong_toast_category", "status_bar_strong_toast",
            "package_name", "island_param", "duration", "target"), byKey.keys)
        assertEquals("headset_wear_notification", byKey.getValue("notifyId").value)
        assertEquals("video_text_text_video", byKey.getValue("strong_toast_category").value)
        assertEquals("show_custom_strong_toast", byKey.getValue("status_bar_strong_toast").value)
        assertEquals("com.xiaomi.bluetooth", byKey.getValue("package_name").value)
        assertEquals("JSON", byKey.getValue("island_param").value)
        assertEquals(5000L, byKey.getValue("duration").value)
        assertEquals(IslandBundleKind.LONG, byKey.getValue("duration").kind)
        assertNull(byKey.getValue("target").value)
        assertEquals(IslandBundleKind.NULL_PARCELABLE, byKey.getValue("target").kind)
        for (key in listOf("notifyId", "strong_toast_category", "status_bar_strong_toast",
                "package_name", "island_param")) {
            assertEquals(key, IslandBundleKind.STRING, byKey.getValue(key).kind)
        }
    }

    @Test
    fun nativeHintWinsBeforeTheThirdPartyIslandOnEveryContract() {
        for (contract in OwnershipContractState.entries) {
            assertEquals(contract.toString(), NativeIslandRoute.NATIVE_SHOWN,
                nativeIslandAdmission(contract, nativeShown = true))
        }
    }

    @Test
    fun thirdPartyIslandIsAdmittedOnlyOnMissingContract() {
        assertEquals(NativeIslandRoute.THIRD_PARTY,
            nativeIslandAdmission(OwnershipContractState.MISSING, nativeShown = false))
        for (contract in listOf(OwnershipContractState.RESOLVED, OwnershipContractState.MISMATCH)) {
            assertEquals("$contract: the renderer must never be reached", NativeIslandRoute.NOT_SHOWN,
                nativeIslandAdmission(contract, nativeShown = false))
        }
    }
}