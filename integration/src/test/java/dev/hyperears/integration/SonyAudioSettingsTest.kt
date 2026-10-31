package dev.hyperears.integration

import dev.hyperears.protocol.sony.SonyHeadphonesWireCodec as Transport
import org.junit.Assert.*
import org.junit.Test

class SonyAudioSettingsTest {
    private fun b(vararg values: Int) = values.map(Int::toByte).toByteArray()
    @Test fun advertisedDseeParameterPublishesDedicatedConfirmedState() {
        val session = SonyDailyControlsSession(true)
        session.accept(b(7, 0, 1, 0xE2, 0))
        val result = session.accept(b(0xE7, 1, 1))
        assertTrue("Confirmed DSEE must have typed state", result?.states?.any { it.featureId == "sony.audio_settings" } == true)
    }

    @Test fun availabilityNeverCrossesInquiredTypesAndWritesReadBackWithoutOptimism() {
        val session = SonyDailyControlsSession(true)
        session.accept(b(7, 0, 2, 0xED, 0, 0xE7, 1))
        session.accept(b(0xE7, 11, 0)); session.accept(b(0xE7, 5, 2, 7))
        val dsee = SonyControlRequest.SetDsee(SonyAudioDefinition(2, 11), true)
        val quality = SonyControlRequest.SetConnectionQuality(SonyAudioDefinition(2, 5), 0)
        assertTrue(session.encode(dsee).isEmpty()); assertTrue(session.encode(quality).isEmpty())
        session.accept(b(0xE3, 11, 0))
        assertEquals(listOf(0xE8, 11, 1), session.encode(dsee).first().u())
        assertEquals(listOf(0xE6, 11), session.encode(dsee)[1].u())
        assertTrue(session.encode(quality).isEmpty())
        session.accept(b(0xE2, 5, 0)) // Echoed GET is not evidence.
        assertTrue(session.encode(quality).isEmpty())
        session.accept(b(0xE3, 5, 0))
        assertEquals(listOf(0xE8, 5, 0), session.encode(quality).first().u())
        assertEquals(2, session.audioState().quality)
        assertEquals(false, session.audioState().dsee)
        session.accept(b(0xE5, 11, 1))
        assertTrue(session.encode(dsee).isEmpty()); assertFalse(session.encode(quality).isEmpty())
        assertTrue(session.encode(quality.copy(mode = 2)).isEmpty())
    }
    @Test fun definitionsChangeRevokesOldReportsAndIpcRequests() {
        val session = SonyDailyControlsSession(true)
        session.accept(b(7, 0, 1, 0xE3, 0)); session.accept(b(0xE7, 2, 1)); session.accept(b(0xE3, 2, 0))
        val old = SonyControlRequest.SetConnectionQuality(SonyAudioDefinition(2, 2), 0)
        assertFalse(session.encode(old).isEmpty())
        session.accept(b(7, 0, 2, 0xE3, 0, 0xE7, 1))
        assertNull(session.audioState().quality)
        assertTrue(session.encode(old).isEmpty())
        session.accept(b(0xE7, 2, 0)); assertNull(session.audioState().quality)
        session.accept(b(0xE7, 5, 1)); session.accept(b(0xE3, 5, 0))
        assertTrue(session.encode(old).isEmpty())
        assertFalse(session.encode(old.copy(expected = SonyAudioDefinition(2, 5))).isEmpty())
        session.accept(b(7, 0, 0)); assertFalse(session.audioState().hasSettings)
    }
    @Test fun speakUsesVersionSpecificPolarityAndStatusIsNotToggle() {
        for (v2 in listOf(false, true)) {
            val session = SonyDailyControlsSession(v2)
            session.accept(if (v2) b(7, 0, 1, 0xFC, 0) else b(7, 0, 1, 0xF5))
            val type = if (v2) 12 else 5
            session.accept(b(0xF3, type, 0, 1))
            assertNull(session.audioState().speak)
            val request = SonyControlRequest.SetSpeakToChat(SonyAudioDefinition(if (v2) 2 else 1, type), true)
            assertTrue(session.encode(request).isEmpty())
            session.accept(if (v2) b(0xF7, type, 1, 1) else b(0xF7, type, 0, 0))
            assertEquals(false, session.audioState().speak)
            assertEquals(if (v2) listOf(0xF8, type, 0, 1) else listOf(0xF8, 5, 1, 1), session.encode(request).first().u())
            assertEquals(listOf(0xF6, type), session.encode(request)[1].u())
            session.accept(b(0xF5, type, 1, 0)); assertTrue(session.encode(request).isEmpty())
            session.accept(b(0xF7, type, 255, 1)); assertEquals(false, session.audioState().speak)
        }
    }
    @Test fun v1DseeQualityAndUnadvertisedReportsNeverGrantControls() {
        val session = SonyDailyControlsSession(false)
        session.accept(b(7, 0, 2, 0xE1, 0xE2))
        assertNull(session.accept(b(0xE7, 1, 1)))
        assertNull(session.accept(b(0xE7, 0, 0)))
        assertFalse(session.audioState().hasSettings)
    }
    @Test fun newRequestsRoundTripWithDefinition() {
        listOf(SonyControlRequest.SetDsee(SonyAudioDefinition(2, 1), true),
            SonyControlRequest.SetConnectionQuality(SonyAudioDefinition(2, 5), 1),
            SonyControlRequest.SetSpeakToChat(SonyAudioDefinition(1, 5), false)).forEach {
            assertEquals(it, ControlRequestTransport.decode(ControlRequestTransport.encode(it)))
        }
    }
    @Test fun adapterRoutesOnlyTableOneAndBusyRejectedAudioNeverQueues() {
        val adapter = requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(deviceName = "WH-1000XM4", standardHeadset = true)))
        adapter.beginHandshake()
        fun report(type: Transport.MessageType = Transport.MessageType.COMMAND_1, vararg p: Int) =
            adapter.receive(Transport.encode(type, 0, b(*p)))
        report(p = intArrayOf(1, 0, 3, 0, 0, 0, 0, 0))
        report(Transport.MessageType.COMMAND_2, 7, 0, 1, 0xE2, 0)
        report(Transport.MessageType.COMMAND_2, 0xE7, 1, 0)
        assertNull(adapter.runtimeState().features.get<SonyAudioSettingsFeatureState>())
        report(p = intArrayOf(7, 0, 1, 0xE2, 0)); report(p = intArrayOf(0xE7, 1, 0))
        val request = SonyControlRequest.SetDsee(SonyAudioDefinition(2, 1), true)
        assertTrue(adapter.supportsControl(request))
        assertFalse(adapter.executeControl(request).accepted)
        var nextAck = 1
        fun drain() = buildList {
            repeat(30) {
                val result = adapter.receive(Transport.encode(Transport.MessageType.ACK, nextAck))
                nextAck = 1 - nextAck
                result.commands.forEach { addAll(Transport.Decoder().offer(it)) }
            }
        }
        assertTrue(drain().none { it.payload.firstOrNull()?.toInt()?.and(255) == 0xE8 })
        val accepted = adapter.executeControl(request)
        assertTrue(accepted.accepted)
        assertEquals(listOf(0xE8, 1, 1), Transport.Decoder().offer(accepted.commands.single()).single().payload.u())
        assertEquals(false, adapter.runtimeState().features.get<SonyAudioSettingsFeatureState>()!!.dsee)
        adapter.resetProtocolSession()
        assertNull(adapter.runtimeState().features.get<SonyAudioSettingsFeatureState>())
        assertFalse(adapter.supportsControl(request))
    }
    @Test fun wearingNeedsFunctionParameterAndAvailabilityAndNeverUpdatesOptimistically() {
        val session=SonyDailyControlsSession(false)
        assertNull(session.accept(b(0xF7,3,0,0)))
        session.accept(b(7,0,1,0xF3))
        session.accept(b(0xF7,3,0,0))
        val request=SonyControlRequest.SetWearingPlayback(SonyAudioDefinition(1,3),false,true)
        assertTrue(session.encode(request).isEmpty())
        session.accept(b(0xF3,3,0))
        assertEquals(request,ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
        assertEquals(listOf(0xF8,3,0,1),session.encode(request).first().u())
        assertEquals(listOf(0xF6,3),session.encode(request)[1].u())
        assertEquals(false,session.audioState().wearingPlayback)
        session.accept(b(0xF9,3,0,1));assertTrue(session.encode(request).isEmpty())
        session.accept(b(0xF5,3,1));assertTrue(session.encode(request.copy(expectedCurrent=true,enabled=false)).isEmpty())
        session.accept(b(7,0,0));assertNull(session.audioState().wearingDefinition);assertNull(session.audioState().wearingPlayback)
        val v2=SonyDailyControlsSession(true)
        v2.accept(b(7,0,1,0xF1,0));v2.accept(b(0xF7,3,0,0));v2.accept(b(0xF3,3,0))
        assertTrue(v2.encode(request).isEmpty())
    }
    private fun ByteArray.u() = map { it.toInt() and 255 }
}
