package dev.hyperears.presentation

import dev.hyperears.integration.BatteryReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeIslandNotificationTest {

    @Test
    fun focusParamJsonMatchesTheHostParamV2KeyTable() {
        // Exact host shape (island-spec §11 L228-238) plus the symmetric imageTextInfoRight carrying
        // both battery artworks (user decision: graphics in the island, text keeps its slot too).
        assertEquals("""{"param_v2":{"protocol":1,"business":"btheadsetnotification","updatable":true,"enableFloat":true,"timeout":1,"ticker":"Mi","tickerPic":"miui.focus.pic_connect_button","param_island":{"islandProperty":1,"islandTimeout":10,"bigIslandArea":{"imageTextInfoLeft":{"type":1,"picInfo":{"type":1,"pic":"hyperears_battery_left"}},"imageTextInfoRight":{"type":1,"picInfo":{"type":1,"pic":"hyperears_battery_right"}},"textInfo":{"title":"L 85% · R 90%"}},"smallIslandArea":{"picInfo":{"type":1,"pic":"hyperears_battery_left"}}},"iconTextInfo":{"type":1,"title":"Mi","content":"L 85% · R 90%","animIconInfo":{"type":0,"src":"hyperears_battery_left"}},"actions":[{"type":2,"actionTitle":"连接","actionTitleColor":"#FF000000","actionTitleColorDark":"#FFFFFF","actionBgColorDark":"#0D84FF","actionIntentType":2,"actionIntent":"URI"}]}}""",
            focusParamJson(FocusIslandText(
                title = "Mi", content = "L 85% · R 90%", actionTitle = "连接", actionIntentUri = "URI",
                leftPic = "hyperears_battery_left", rightPic = "hyperears_battery_right",
            )))
    }

    @Test
    fun focusParamJsonKeepsTheHostShapeWhenArtworkDegrades() {
        // A failed battery drawing (or a missing reading) must reproduce the untouched host §11 form:
        // earphone key left, no imageTextInfoRight object at all.
        assertEquals("""{"param_v2":{"protocol":1,"business":"btheadsetnotification","updatable":true,"enableFloat":true,"timeout":1,"ticker":"Mi","tickerPic":"miui.focus.pic_connect_button","param_island":{"islandProperty":1,"islandTimeout":10,"bigIslandArea":{"imageTextInfoLeft":{"type":1,"picInfo":{"type":1,"pic":"miui.focus.pic_earphone"}},"textInfo":{"title":"已连接"}},"smallIslandArea":{"picInfo":{"type":1,"pic":"miui.focus.pic_earphone"}}},"iconTextInfo":{"type":1,"title":"Mi","content":"已连接","animIconInfo":{"type":0,"src":"miui.focus.pic_earphone"}},"actions":[{"type":2,"actionTitle":"连接","actionTitleColor":"#FF000000","actionTitleColorDark":"#FFFFFF","actionBgColorDark":"#0D84FF","actionIntentType":2,"actionIntent":"URI"}]}}""",
            focusParamJson(FocusIslandText(
                title = "Mi", content = "已连接", actionTitle = "连接", actionIntentUri = "URI",
                leftPic = "miui.focus.pic_earphone", rightPic = null,
            )))
    }

    @Test
    fun picKeysRequireBothAReadingAndSuccessfulArtwork() {
        val ear = BatteryReading(85, false)
        val missing = BatteryReading(null, false)
        assertEquals("hyperears_battery_left", islandLeftPicKey(ear, artworkAvailable = true))
        // Fallback chain: no artwork drawn -> the JSON never references the missing pics key.
        assertEquals("miui.focus.pic_earphone", islandLeftPicKey(ear, artworkAvailable = false))
        assertEquals("miui.focus.pic_earphone", islandLeftPicKey(missing, artworkAvailable = true))
        assertEquals("hyperears_battery_right", islandRightPicKey(ear, artworkAvailable = true))
        assertNull(islandRightPicKey(ear, artworkAvailable = false))
        assertNull(islandRightPicKey(missing, artworkAvailable = true))
    }

    @Test
    fun batteryDrawingDecisionsArePinned() {
        assertEquals(0.85, batteryFillRatio(85).toDouble(), 1e-4)
        assertEquals(0.0, batteryFillRatio(null).toDouble(), 1e-4)
        assertEquals(1.0, batteryFillRatio(150).toDouble(), 1e-4) // clamped
        assertEquals("49%", batteryLabel(49))
        assertEquals("-", batteryLabel(null))
        assertEquals(0xFFE5392B.toInt(), batteryFillColorRgb(20)) // the §4 threshold
        assertEquals(0xFFE5392B.toInt(), batteryFillColorRgb(0))
        assertEquals(0xFF2196F3.toInt(), batteryFillColorRgb(21))
        assertEquals(0xFF2196F3.toInt(), batteryFillColorRgb(100))
    }

    @Test
    fun focusExtrasKeysCarryTheHostTriple() {
        assertEquals(listOf("miui.focus.param", "miui.focus.pics", "miui.focus.actions"), focusExtrasKeys())
    }

    @Test
    fun islandContentPrefersBatteryAndFallsBackToTheStatusString() {
        assertEquals("L 85% · R 90%",
            focusIslandContent(BatteryReading(85, false), BatteryReading(90, false), "已连接"))
        assertEquals("R 49%", focusIslandContent(BatteryReading(null, false), BatteryReading(49, false), "已连接"))
        assertEquals("L 49%", focusIslandContent(BatteryReading(49, false), BatteryReading(null, false), "已连接"))
        assertEquals("已连接", focusIslandContent(BatteryReading(null, false), BatteryReading(null, false), "已连接"))
    }

    @Test
    fun submissionIdentityAndCancelPolicyFollowTheHostSlot() {
        assertEquals(999, NativeIslandNotification.NOTIFY_ID)
        assertEquals(5_000L, NativeIslandNotification.CANCEL_DELAY_MS)
    }

    @Test
    fun delayedCancelOnlyRemovesOurOwnNotification() {
        assertTrue(shouldCancelFocusNotification("MINE", "MINE"))
        assertFalse(shouldCancelFocusNotification("HOST", "MINE"))
        assertFalse(shouldCancelFocusNotification("NEWER", "MINE"))
        assertFalse(shouldCancelFocusNotification(null, "MINE"))
    }
}
