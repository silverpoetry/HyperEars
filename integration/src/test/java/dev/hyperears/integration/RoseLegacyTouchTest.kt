package dev.hyperears.integration

import dev.hyperears.protocol.rose.RoseLegacyIdentityCodec.Model
import org.junit.Assert.*
import org.junit.Test

class RoseLegacyTouchTest {
    // Independent complete source-offset fixture: selected frame bytes5/7/9/15/17/19.
    private val golden="DD 00 11 01 A0 B0 A1 B1 A2 B2 A3 B3 A4 B4 A5 B0 A6 B1 A7 B2 98 AA"
        .split(" ").map{it.toInt(16).toByte()}.toByteArray()
    private fun data()=golden.sliceArray(4 until 20).map{it.toInt()and 255}.toMutableList()
    @Test fun literalSourceFrameUsesSixIndependentOffsets() {
        val adapter=RoseLegacyAdapter(Model.OPENLINK)
        adapter.receive(golden)
        val report=adapter.runtimeState().features.get<RoseLegacySettingsFeatureState>()!!.touch!!
        assertEquals(listOf(0,1,2,0,1,2),(0..5).map{RoseLegacyTouch.action(report,it)})
    }
    private fun frame(data:List<Int>):ByteArray {
        val body=(listOf(0xdd,0,data.size+1,1)+data).map(Int::toByte).toByteArray()
        return body+byteArrayOf(body.sumOf{it.toInt()and 255}.toByte(),0xaa.toByte())
    }
    @Test fun completeDirectReportUnlocksSixSlotsAndPinsOpaqueSiblingEvidence() {
        val adapter=RoseLegacyAdapter(Model.OPENLINK)
        val snapshot=data()
        val request=RoseLegacyControlRequest.SetTouch(Model.OPENLINK.name,snapshot,2,4)
        assertFalse(adapter.supportsControl(request))
        adapter.receive(frame(snapshot))
        assertTrue(adapter.supportsControl(request))
        assertEquals(request,ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
        assertEquals(snapshot,adapter.runtimeState().features.get<RoseLegacySettingsFeatureState>()!!.touch)
        assertTrue(adapter.executeControl(request).accepted)
        assertEquals(snapshot,adapter.runtimeState().features.get<RoseLegacySettingsFeatureState>()!!.touch)
        adapter.receive(frame(snapshot.toMutableList().apply{this[0]=99}))
        assertFalse(adapter.supportsControl(request))
        adapter.resetProtocolSession();assertFalse(adapter.supportsControl(request))
    }
    @Test fun lowNibbleActionsRetainUpperNibbleAsStaleRequestEvidence() {
        val snapshot=data().apply{this[1]=0xa1}
        val session=RoseLegacySession(Model.OPENLINK)
        session.offer(frame(snapshot))
        val request=RoseLegacyControlRequest.SetTouch(Model.OPENLINK.name,snapshot,1,2)
        assertFalse(session.encode(request).isEmpty())
        assertTrue(session.encode(request.copy(action=1)).isEmpty())
        session.offer(frame(snapshot.toMutableList().apply{this[1]=0xb1}))
        assertTrue(session.encode(request).isEmpty())
    }
    @Test fun malformedShortUnknownActionAndF8NeverGrantMapping() {
        for(model in listOf(Model.OPENLINK,Model.AGATE)) {
            val adapter=RoseLegacyAdapter(model)
            adapter.receive(frame(data().take(15)))
            assertNull(adapter.runtimeState().features.get<RoseLegacySettingsFeatureState>()?.touch)
            adapter.receive(frame(data().toMutableList().apply{this[1]=255}))
            assertNull(adapter.runtimeState().features.get<RoseLegacySettingsFeatureState>()?.touch)
        }
    }
    @Test fun modelActionDomainsAndStaleDirectSessionReject() {
        val snapshot=data()
        for(model in Model.entries.filterNot{it.f8}) {
            val session=RoseLegacySession(model)
            session.offer(frame(snapshot))
            val request=RoseLegacyControlRequest.SetTouch(model.name,snapshot,1,5)
            val bytes=session.encode(request).single().map{it.toInt()and 255}
            assertEquals(listOf(3,1,1,5),bytes.subList(2,6))
            assertTrue(session.readback(request).isNotEmpty())
            assertTrue(session.encode(request.copy(slot=4)).isEmpty())
            assertTrue(session.encode(request.copy(expectedModel=Model.AGATE.name)).isEmpty())
            session.offer(frame(snapshot.toMutableList().apply{this[3]=3}))
            assertTrue(session.encode(request).isEmpty())
        }
        val buds=RoseLegacySession(Model.BUDSFREE);buds.offer(frame(snapshot))
        assertTrue(buds.encode(RoseLegacyControlRequest.SetTouch(Model.BUDSFREE.name,snapshot,1,4)).isEmpty())
        assertFalse(buds.encode(RoseLegacyControlRequest.SetTouch(Model.BUDSFREE.name,snapshot,1,6)).isEmpty())
    }
}
