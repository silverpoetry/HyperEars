package dev.hyperears.integration
import dev.hyperears.protocol.rose.RoseLegacyIdentityCodec.Model
import org.junit.Assert.*
import org.junit.Test
class RoseLegacyF8TouchTest {
    // Independent source full-frame positions: 9,15,21,27,12,18,24,30.
    private val golden="00 27 02 00 1A 05 18 01 01 A0 02 01 B5 03 01 C6 04 01 D3 05 01 E4 06 01 F7 07 01 A9 08 01 B2"
        .split(" ").map{it.toInt(16).toByte()}.toByteArray()
    @Test fun sourceHandlerEightSlotsAndExactQueryWriteReadback() {
        for(model in listOf(Model.EXPLORER,Model.CERAMICS_X_7034)) {
            val session=RoseLegacySession(model)
            val query=byteArrayOf(2,0x27,1,0,1,5)
            assertTrue(session.initialReadCommands().any{it.contentEquals(query)})
            val events=session.offer(golden)
            val feature=events.filterIsInstance<ProtocolEvent.FeatureStateChanged>().map{it.state}.filterIsInstance<RoseLegacySettingsFeatureState>().single()
            assertEquals(listOf(0,6,4,9,5,3,7,2),(0..7).map{RoseLegacyTouch.action(feature.touch!!,it,model)})
            val request=RoseLegacyControlRequest.SetTouch(model.name,feature.touch!!,4,7)
            assertArrayEquals(byteArrayOf(0,0x22,1,0,3,7,1,7),session.encode(request).single())
            assertArrayEquals(query,session.readback(request).single())
            assertTrue(session.encode(request.copy(action=1)).isEmpty())
            session.offer(golden.copyOf().apply{this[8]=2})
            assertTrue(session.encode(request).isEmpty())
            session.reset();assertTrue(session.encode(request).isEmpty())
        }
    }
    @Test fun outgoingOrUnprovenMarkerAndScalarAckNeverGrantMapping() {
        for(raw in listOf(byteArrayOf(2,0x27,1,0,1,5),golden.copyOf().apply{this[0]=2},byteArrayOf(0,0x28,2,0,3,5,1,7))) {
            val adapter=RoseLegacyAdapter(Model.EXPLORER);adapter.receive(raw)
            assertNull(adapter.runtimeState().features.get<RoseLegacySettingsFeatureState>()?.touch)
        }
    }
    @Test fun agateAndShortOrInvalidReportsDoNotGrantTouch() {
        for(model in listOf(Model.AGATE,Model.AGATE_X,Model.CERAMICS)) {
            val adapter=RoseLegacyAdapter(model);adapter.receive(golden)
            assertNull(adapter.runtimeState().features.get<RoseLegacySettingsFeatureState>()?.touch)
        }
        val adapter=RoseLegacyAdapter(Model.EXPLORER)
        adapter.receive(golden.copyOf().apply{this[9]=0xaf.toByte()})
        assertNull(adapter.runtimeState().features.get<RoseLegacySettingsFeatureState>()?.touch)
        adapter.receive(golden.take(30).toByteArray())
        assertNull(adapter.runtimeState().features.get<RoseLegacySettingsFeatureState>()?.touch)
    }
}
