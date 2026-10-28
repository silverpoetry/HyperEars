package dev.hyperears.integration
import org.junit.Assert.*
import org.junit.Test

class HuaweiIndependentSwipeTest {
    private fun hex(s:String)=s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun adapter(name:String)=requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(name,true)))
    private fun gesture(a:EarbudAdapter)=a.runtimeState().features.get<HuaweiDailySettingsFeatureState>()!!.gestures.single { it.kind==HuaweiGestureKind.SWIPE }
    private fun request(a:EarbudAdapter,side:HuaweiGestureSide,value:Int)=HuaweiDailyControlRequest.SetGesture(HuaweiGestureKind.SWIPE,side,value,gesture(a))
    @Test fun eachQualifiedProfileWritesOneSideAndPinsCompleteModelPair() {
        for(name in listOf("HUAWEI FreeArc","HUAWEI FreeBuds 6i","HUAWEI FreeClip 2")) {
            val a=adapter(name)
            assertTrue(a.beginHandshake().commands.any { it.contentEquals(hex("5A0007002B1F01000200328A")) })
            // Upstream captured FreeClip2 state, independent of production builder/constants.
            a.receive(hex("5A000E002B1F01010002010003030001FF24DF"))
            val target=if(name.endsWith("FreeArc"))1 else -1
            val r=request(a,HuaweiGestureSide.RIGHT,target)
            assertTrue(a.supportsControl(r))
            assertFalse(a.supportsControl(r.copy(expected=null)))
            assertTrue(a.protocolSession.encode(r.copy(expected=null)).isEmpty())
            val sent=a.executeControl(r)
            val frame=dev.hyperears.protocol.huawei.HuaweiFreebudsSppCodec.parseFrame(sent.commands.single())!!
            assertEquals(setOf(2),frame.params.keys)
            assertArrayEquals(byteArrayOf(target.toByte()),frame.params[2])
            assertArrayEquals(hex("5A0007002B1F01000200328A"),sent.readback.single())
            assertEquals(0,gesture(a).right)
            assertFalse(a.supportsControl(r.copy(expected=r.expected!!.copy(modelId="another-model"))))
            a.resetProtocolSession()
            assertFalse(a.supportsControl(r));assertTrue(a.protocolSession.encode(r).isEmpty())
        }
    }
    @Test fun unknownSiblingIsPreservedAndChangedSiblingOrPartialRevokesOldRequest() {
        val a=adapter("HUAWEI FreeArc")
        a.receive(hex("5A000E002B1F01010702010003030001FF9574"))
        assertEquals(7,gesture(a).left)
        val r=request(a,HuaweiGestureSide.RIGHT,1)
        assertArrayEquals(hex("5A0006002B1E020101525C"),a.executeControl(r).commands.single())
        assertFalse(a.supportsControl(r.copy(value=7)))
        a.receive(hex("5A000E002B1F01010802010003030001FF0D20"))
        assertFalse(a.supportsControl(r));assertTrue(a.protocolSession.encode(r).isEmpty())
        val latest=request(a,HuaweiGestureSide.LEFT,0)
        a.receive(hex("5A0007002B1F010200003A88")) // malformed field width preserves valid report
        assertTrue(a.supportsControl(latest))
        a.receive(hex("5A0006002B1F0101006D99")) // valid incomplete snapshot cannot retain old pair
        assertFalse(a.supportsControl(latest));assertTrue(a.protocolSession.encode(latest).isEmpty())
    }
    @Test fun opaqueReportChangesInvalidateExpectedPairAcrossTransport() {
        val a=adapter("HUAWEI FreeArc")
        a.receive(hex("5A0011002B1F01010002010003030001FF09010111DB"))
        val r=request(a,HuaweiGestureSide.LEFT,1)
        assertEquals(r,ControlRequestTransport.decode(ControlRequestTransport.encode(r)))
        a.receive(hex("5A0011002B1F01010002010003030001FF09010221B8"))
        assertFalse(a.supportsControl(r));assertTrue(a.protocolSession.encode(r).isEmpty())
    }
    @Test fun legacyMirroredAndUnqualifiedEyewearRemainSeparate() {
        val old=adapter("HUAWEI FreeBuds Pro 3")
        old.receive(hex("5A000E002B1F01010002010003030001FF24DF"))
        val r=HuaweiDailyControlRequest.SetGesture(HuaweiGestureKind.SWIPE,HuaweiGestureSide.LEFT,-1)
        val frame=dev.hyperears.protocol.huawei.HuaweiFreebudsSppCodec.parseFrame(old.executeControl(r).commands.single())!!
        assertEquals(setOf(1,2),frame.params.keys)
        val eyewear=adapter("HUAWEI Eyewear 2")
        eyewear.receive(hex("5A000E002B1F01010002010003030001FF24DF"))
        assertFalse(eyewear.supportsControl(r))
    }
}
