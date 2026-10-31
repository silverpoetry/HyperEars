package dev.hyperears.integration
import org.junit.Assert.*
import org.junit.Test
class SonyDevicesTest {
    private fun b(vararg n:Int)=n.map(Int::toByte).toByteArray()
    private val address="00:11:22:33:44:55"
    private fun report(connected:Int)=b(0x37,0,1)+address.toByteArray()+b(connected,4)+"Test".toByteArray()+b(connected)
    @Test fun completeListAndStatusRequiredAndWriteStaysTableTwoPayload() {
        val s=SonyDevicesSession();s.accept(b(7,0,1,0x30,0));s.accept(b(0x31,0,8,2,0))
        s.accept(report(0));s.accept(b(0x33,0,0,0))
        val request=SonyControlRequest.ManageDevices(s.state,SonyDeviceAction.CONNECT,address)
        assertEquals(request,ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
        assertArrayEquals(b(0x3C,0,1)+address.toByteArray(),s.encode(request).first())
        s.accept(report(1));assertTrue(s.encode(request).isEmpty())
        val disconnect=request.copy(baseline=s.state,action=SonyDeviceAction.DISCONNECT)
        assertFalse(s.encode(disconnect).isEmpty())
        s.accept(b(7,0,0));assertTrue(s.encode(disconnect).isEmpty())
    }
    @Test fun nativeHandoverAndSourceKeepUseDifferentWirePolarities() {
        val s=SonyDevicesSession();s.accept(b(7,0,3,0x30,0,0x31,1,0x34,2))
        s.accept(b(0x31,0,8,2,0));s.accept(report(1));s.accept(b(0x33,0,0,0))
        s.accept(b(0x37,1,1));s.accept(b(0x37,3,0))
        assertEquals(false,s.state.sourceKeep);assertEquals(false,s.state.musicHandover)
        assertArrayEquals(b(0x38,1,0),s.encode(SonyControlRequest.ManageDevices(s.state,SonyDeviceAction.SOURCE_KEEP,enabled=true)).first())
        assertArrayEquals(b(0x38,3,1),s.encode(SonyControlRequest.ManageDevices(s.state,SonyDeviceAction.MUSIC_HANDOVER,enabled=true)).first())
        assertArrayEquals(b(0x34,0,1,0),s.encode(SonyControlRequest.ManageDevices(s.state,SonyDeviceAction.PAIRING,enabled=true)).first())
    }
    @Test fun actualAdapterRoutesWritesToTableTwoAndRejectsBusyQueueWithoutDeferral() {
        val adapter=requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(deviceName="WH-1000XM6",standardHeadset=true)))
        val t1=dev.hyperears.protocol.sony.SonyHeadphonesWireCodec.MessageType.COMMAND_1
        val t2=dev.hyperears.protocol.sony.SonyHeadphonesWireCodec.MessageType.COMMAND_2
        fun deliver(table:dev.hyperears.protocol.sony.SonyHeadphonesWireCodec.MessageType,p:ByteArray)=
            adapter.receive(dev.hyperears.protocol.sony.SonyHeadphonesWireCodec.encode(table,0,p))
        adapter.beginHandshake();deliver(t1,b(1,0,3,0,0,0,0,0))
        deliver(t1,b(7,0,1,0x30,0));deliver(t1,b(0x31,0,8,2,0))
        assertNull(adapter.runtimeState().features.get<SonyDevicesFeatureState>())
        deliver(t2,b(7,0,1,0x30,0));deliver(t2,b(0x31,0,8,2,0));deliver(t2,report(0));deliver(t2,b(0x33,0,0,0))
        val state=adapter.runtimeState().features.get<SonyDevicesFeatureState>()!!
        val json=kotlinx.serialization.json.Json
        assertEquals(state,json.decodeFromString(DeviceFeatureState.serializer(),json.encodeToString(DeviceFeatureState.serializer(),state)))
        val request=SonyControlRequest.ManageDevices(state,SonyDeviceAction.CONNECT,address)
        assertTrue(adapter.supportsControl(request));assertFalse(adapter.executeControl(request).accepted)
        var ack=1
        repeat(60) {
            val result=adapter.receive(dev.hyperears.protocol.sony.SonyHeadphonesWireCodec.encode(dev.hyperears.protocol.sony.SonyHeadphonesWireCodec.MessageType.ACK,ack));ack=1-ack
            result.commands.forEach {bytes->dev.hyperears.protocol.sony.SonyHeadphonesWireCodec.Decoder().offer(bytes).forEach {frame->
                assertFalse(frame.payload.firstOrNull()==0x3C.toByte())
            }}
        }
        val result=adapter.executeControl(request);assertTrue(result.accepted)
        val frame=dev.hyperears.protocol.sony.SonyHeadphonesWireCodec.Decoder().offer(result.commands.single()).single()
        assertEquals(t2,frame.type);assertArrayEquals(b(0x3C,0,1)+address.toByteArray(),frame.payload)
        assertEquals(state,adapter.runtimeState().features.get<SonyDevicesFeatureState>())
        adapter.resetProtocolSession();assertFalse(adapter.supportsControl(request))
        assertNull(adapter.runtimeState().features.get<SonyDevicesFeatureState>())
    }
    @Test fun invalidOrIncompleteDeviceListsDoNotBecomeTargets() {
        val s=SonyDevicesSession();assertNull(s.accept(report(0)))
        s.accept(b(7,0,1,0x30,0));s.accept(b(0x31,0,8,2,0));s.accept(b(0x33,0,0,0))
        s.accept(report(0).dropLast(1).toByteArray());assertNull(s.state.devices)
        s.accept(report(0));val request=SonyControlRequest.ManageDevices(s.state,SonyDeviceAction.CONNECT,address)
        assertTrue(s.encode(request.copy(address="AA:BB:CC:DD:EE:FF")).isEmpty())
        s.accept(b(0x35,0,0,1));assertTrue(s.encode(request.copy(baseline=s.state)).isEmpty())
    }
}
