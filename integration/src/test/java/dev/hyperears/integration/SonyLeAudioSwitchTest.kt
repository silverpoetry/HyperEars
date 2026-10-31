package dev.hyperears.integration
import org.junit.Assert.*
import org.junit.Test
class SonyLeAudioSwitchTest {
    @Test fun enablingWaitsForProfileHistoryAndRealSettingNotification() {
        val s = SonyLeAudioSwitch()
        val ticket = requireNotNull(s.begin("session", true, 0)).ticket
        assertNull(s.begin("session", false, 1))
        assertNull(s.history(ticket, 1, 1))
        assertEquals(SonyLeAudioSwitch.Effect.History(ticket), s.profile(ticket, true, false, 2))
        assertNull(s.history(ticket, null, 3))
        assertEquals(SonyLeAudioSwitch.Effect.Write(ticket, true), s.history(ticket, 1, 4))
        assertNull(s.setting(ticket, true, false, 5))
        assertNull(s.setting(ticket, false, true, 6))
        assertEquals(SonyLeAudioSwitch.Effect.Complete(ticket, true, true), s.setting(ticket, true, true, 7))
        assertNull(s.timeout(ticket, 20_000))
    }
    @Test fun existingBondDoesNotRequestPairingAndDisableDoesNotWaitForHistory() {
        val s = SonyLeAudioSwitch()
        var t = requireNotNull(s.begin("s", true, 0)).ticket
        s.profile(t, true, true, 1); s.history(t, 0, 2)
        assertEquals(SonyLeAudioSwitch.Effect.Complete(t, true, false), s.setting(t, true, true, 3))
        t = requireNotNull(s.begin("s", false, 4)).ticket
        assertEquals(SonyLeAudioSwitch.Effect.Write(t, false), s.profile(t, true, true, 5))
        assertEquals(SonyLeAudioSwitch.Effect.Complete(t, false, false), s.setting(t, false, true, 6))
    }
    @Test fun oldCallbacksAndTimersCannotCompleteNewAttempt() {
        val s = SonyLeAudioSwitch()
        val old = requireNotNull(s.begin("s", true, 0)).ticket
        s.cancel("other"); assertNull(s.begin("s", true, 1))
        s.cancel("s")
        val fresh = requireNotNull(s.begin("s", true, 2)).ticket
        assertNull(s.profile(old, true, true, 3)); assertNull(s.timeout(old, 30_000))
        assertEquals(SonyLeAudioSwitch.Effect.History(fresh), s.profile(fresh, true, true, 4))
    }
    @Test fun timeoutOrUnsupportedPlatformNeverProducesWriteOrSuccess() {
        val s = SonyLeAudioSwitch()
        var t = requireNotNull(s.begin("s", true, 0)).ticket
        assertTrue(s.profile(t, false, false, 1) is SonyLeAudioSwitch.Effect.Failed)
        assertNull(s.history(t, 1, 2))
        t = requireNotNull(s.begin("new", true, 3)).ticket
        assertTrue(s.profile(t, true, false, 15_003) is SonyLeAudioSwitch.Effect.Failed)
        assertNull(s.timeout(t, 30_000))
    }
}
