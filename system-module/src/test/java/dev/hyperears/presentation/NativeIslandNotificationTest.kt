package dev.hyperears.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeIslandNotificationTest {

    @Test
    fun focusParamJsonMatchesTheHostParamV2KeyTable() {
        // Exact host shape (island-spec §11): key order is the host's org.json put order.
        assertEquals("""{"param_v2":{"protocol":1,"business":"btheadsetnotification","updatable":true,"enableFloat":true,"timeout":1,"ticker":"Air","tickerPic":"miui.focus.pic_connect_button","param_island":{"islandProperty":1,"islandTimeout":10,"bigIslandArea":{"imageTextInfoLeft":{"type":1,"picInfo":{"type":1,"pic":"miui.focus.pic_earphone"}},"textInfo":{"title":"Ti"}},"smallIslandArea":{"picInfo":{"type":1,"pic":"miui.focus.pic_earphone"}}},"iconTextInfo":{"type":1,"title":"Ti","content":"L 85%","animIconInfo":{"type":0,"src":"miui.focus.pic_earphone"}}}}""",
            focusParamJson(FocusIslandText(ticker = "Air", title = "Ti", content = "L 85%")))
    }

    @Test
    fun focusExtrasCarryOnlyTheParamKeyOnTheFirstRound() {
        // v1 ships no actions/pics bundles (spec §11 reproduction decision): the key table is pinned.
        val entries = focusExtrasEntries("JSON")
        assertEquals(1, entries.size)
        assertEquals("miui.focus.param", entries.single().key)
        assertEquals("JSON", entries.single().value)
    }

    @Test
    fun submissionIdentityAndCancelPolicyFollowTheHostSlot() {
        // Same tag as the channel and id 0x3e9 the host uses; the resident host slot is cancelled for
        // a third-party text island after the island window (the 5 s connect duration) elapsed.
        assertEquals(999, NativeIslandNotification.NOTIFY_ID)
        assertEquals(5_000L, NativeIslandNotification.CANCEL_DELAY_MS)
    }

    @Test
    fun delayedCancelOnlyRemovesOurOwnNotification() {
        // Review Minor: ownership-gated retraction. Equal param cancels; a foreign param (the host's
        // own post on the real AirPods path), a newer generation's param, or a missing slot (null)
        // are all retained.
        assertTrue(shouldCancelFocusNotification("MINE", "MINE"))
        assertFalse(shouldCancelFocusNotification("HOST", "MINE"))
        assertFalse(shouldCancelFocusNotification("NEWER", "MINE"))
        assertFalse(shouldCancelFocusNotification(null, "MINE"))
    }
}
