package dev.hyperears.integration
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RayNeoCaptureTest {
    private fun offer(session: RayNeoProtocolSession, message: String) = RayNeoLaunchCodec.fragment(message).flatMap(session::offer)
    @Test fun capabilitiesAndRealReportsGateRecordingAndNeverTreatWriteAsSuccess() {
        val session = RayNeoProtocolSession()
        val start = RayNeoControlRequest.SetRecording(true)
        assertTrue(session.encode(start).isEmpty())
        offer(session,"""{"uri":"ble.request.general.settings","generalSettings":{"deviceType":"RayNeoX3Pro","productModel":"ARGF20","supportMixShot":true,"supportMixRecord":true}}""")
        assertFalse(session.encode(start).isEmpty())
        assertFalse(session.encode(RayNeoControlRequest.CaptureShot).isEmpty())
        val countdown = offer(session,"""{"uri":"ble.request.mix_record","type":3}""").filterIsInstance<ProtocolEvent.FeatureStateChanged>().single().state as RayNeoCaptureFeatureState
        assertEquals(RayNeoRecordingState.COUNTDOWN,countdown.recording)
        assertTrue(session.encode(start).isEmpty())
        val active = offer(session,"""{"uri":"ble.request.mix_record","type":2,"timestamp":12345}""").filterIsInstance<ProtocolEvent.FeatureStateChanged>().single().state as RayNeoCaptureFeatureState
        assertEquals(RayNeoRecordingState.RECORDING,active.recording)
        assertFalse(session.encode(RayNeoControlRequest.SetRecording(false)).isEmpty())
        val error = offer(session,"""{"uri":"ble.request.mix_record","type":999}""").filterIsInstance<ProtocolEvent.FeatureStateChanged>().single().state as RayNeoCaptureFeatureState
        assertNull(error.recording)
        assertEquals(999,error.recordResult)
        session.reset()
        assertTrue(session.encode(start).isEmpty())
    }
    @Test fun captureCommandsHaveVerifiedDirectionsAndNoTransferPayload() {
        val start = Json.parseToJsonElement(RayNeoCaptureCodec.command(false,true)).jsonObject
        val stop = Json.parseToJsonElement(RayNeoCaptureCodec.command(false,false)).jsonObject
        assertEquals(JsonPrimitive("ble.response.mix_record"),start["uri"])
        assertEquals(JsonPrimitive(0),start["type"])
        assertEquals(JsonPrimitive(1),stop["type"])
        assertEquals(setOf("uri","id","type"),start.keys)
        assertNull(RayNeoCaptureCodec.parse("""{"uri":"ble.request.mix_shot","type":1}"""))
        assertEquals(RayNeoControlRequest.CaptureShot,ControlRequestTransport.decode(ControlRequestTransport.encode(RayNeoControlRequest.CaptureShot)))
    }

    @Test fun shotRequiresPreparationThenSingleCommitAndResetCancelsIt() {
        var now = 0L
        val session = RayNeoProtocolSession { now }
        val settings = """{"uri":"ble.request.general.settings","generalSettings":{"deviceType":"RayNeoX3Pro","productModel":"ARGF20","supportMixShot":true}}"""
        val query = TelemetryQuery.RefreshFeature(RayNeoCaptureCodec.SHOT_COMMIT_QUERY)
        offer(session, settings)
        assertFalse(session.encode(RayNeoControlRequest.CaptureShot).isEmpty())
        assertTrue(session.encode(RayNeoControlRequest.CaptureShot).isEmpty())
        assertTrue(session.query(query).isEmpty())
        now = 4_000
        val decoder = dev.hyperears.protocol.rayneo.RayNeoBleWireCodec.Decoder()
        val message = session.query(query).mapNotNull(decoder::offer).single()
        assertEquals(JsonPrimitive(10), Json.parseToJsonElement(message).jsonObject["type"])
        assertTrue(session.query(query).isEmpty())
        offer(session, """{"uri":"ble.request.mix_shot","type":0}""")
        assertFalse(session.encode(RayNeoControlRequest.CaptureShot).isEmpty())
        session.reset()
        now = 8_000
        assertTrue(session.query(query).isEmpty())
    }
    @Test fun shotErrorCancelsDelayedCapture() {
        var now = 0L
        val session = RayNeoProtocolSession { now }
        offer(session, """{"uri":"ble.request.general.settings","generalSettings":{"deviceType":"RayNeoX3Pro","productModel":"ARGF20","supportMixShot":true}}""")
        session.encode(RayNeoControlRequest.CaptureShot)
        offer(session, """{"uri":"ble.request.mix_shot","type":101}""")
        now = 4_000
        assertTrue(session.query(TelemetryQuery.RefreshFeature(RayNeoCaptureCodec.SHOT_COMMIT_QUERY)).isEmpty())
    }
}
