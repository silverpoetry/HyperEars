package dev.hyperears.runtime
import dev.hyperears.integration.SonyLeAudioIdentity
import org.junit.Assert.*
import org.junit.Test
class SonyLeAudioPresenceTest {
    private val classic="AA:BB:CC:DD:EE:01"
    private val le="AA:BB:CC:DD:EE:02"
    private val identity=SonyLeAudioIdentity(classic,listOf(le))
    @Test fun identityMustContainTheReportingDeviceAndCannotStealAnotherGroup() {
        val model=SonyLeAudioPresence()
        assertFalse(model.learn("AA:BB:CC:DD:EE:03",identity))
        assertNull(model.classicFor(le))
        assertTrue(model.learn(classic,identity))
        assertEquals(classic,model.classicFor(le.lowercase()))
        assertFalse(model.learn("AA:BB:CC:DD:EE:03",SonyLeAudioIdentity("AA:BB:CC:DD:EE:03",listOf(le))))
        assertEquals(classic,model.classicFor(le))
        assertFalse(model.learn(classic,identity.copy(le=listOf(classic))))
    }
    @Test fun leConnectionKeepsSessionAliveAcrossClassicDisconnect() {
        val model=SonyLeAudioPresence();model.learn(classic,identity)
        model.a2dp(classic,true);assertEquals(SonyLeAudioPresence.Route(classic,classic,false),model.route(classic))
        model.leAudio(le,true);assertEquals(SonyLeAudioPresence.Route(classic,le,true),model.route(classic))
        model.a2dp(classic,false);assertEquals(le,model.route(classic)?.controlAddress)
        model.leAudio(le,false);assertNull(model.route(classic))
    }
    @Test fun classicConnectionSurvivesLeDisconnectAndEarlyLeEventsAreRetained() {
        val model=SonyLeAudioPresence();model.leAudio(le,true)
        assertNull(model.route(classic))
        model.learn(le,identity)
        assertEquals(le,model.route(classic)?.controlAddress)
        model.a2dp(classic,true);model.leAudio(le,false)
        assertEquals(classic,model.route(classic)?.controlAddress)
        assertEquals(false,model.route(classic)?.preferGatt)
    }
    @Test fun updatedIdentityRemovesOldAliasAndUnpairForgetsWholeGroup() {
        val model=SonyLeAudioPresence();model.learn(classic,identity)
        val next="AA:BB:CC:DD:EE:04"
        model.learn(classic,identity.copy(le=listOf(next)))
        assertNull(model.classicFor(le));assertEquals(classic,model.classicFor(next))
        model.a2dp(classic,true);model.leAudio(next,true)
        model.forget(next)
        assertNull(model.classicFor(classic));assertNull(model.route(classic))
        model.learn(classic,identity.copy(le=listOf(next)))
        assertNull(model.route(classic))
    }
    @Test fun closeClearsBothPresenceAndIdentity() {
        val model=SonyLeAudioPresence();model.learn(classic,identity);model.leAudio(le,true)
        model.clear();assertNull(model.classicFor(le));assertNull(model.route(classic))
    }
}
