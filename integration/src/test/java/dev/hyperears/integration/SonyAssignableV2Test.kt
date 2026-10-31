package dev.hyperears.integration
import org.junit.Assert.*
import org.junit.Test
class SonyAssignableV2Test {
    private fun b(vararg p:Int)=p.map(Int::toByte).toByteArray()
    private fun capability(type:Int)=(listOf(0xF1,type)+(if(type==14)listOf(1)else emptyList())+
        listOf(2,0,0,32,1,32,0,1,0,32,2,32,33,1,0,16,1,16,0,1,0,35,2,35,36)).map(Int::toByte).toByteArray()
    private fun ready(type:Int):SonyAssignableSession {
        val s=SonyAssignableSession(true)
        s.accept(b(7,0,1,if(type==3)0xF3 else 0xFE,0));s.accept(capability(type))
        s.accept(b(0xF7,type,2,32,16));s.accept(b(0xF3,type,2,0,0))
        return s
    }
    private fun request(s:SonyAssignableSession)=SonyControlRequest.SetAssignableAction(s.state.definition!!,s.state.current!!,s.state.mappings!!,0,0,33)
    @Test fun completeReportedMappingsRequiredAndWritePreservesSiblingAndReadbacks() {
        for(type in listOf(3,14)) {
            val s=ready(type);assertFalse(s.state.completeMappings)
            s.accept(b(0xFB,type,1,32,1,0,32));assertFalse(s.state.completeMappings)
            s.accept(b(0xFB,type,2,32,1,0,32,16,1,0,35));assertTrue(s.state.completeMappings)
            val request=request(s);val before=s.state
            assertEquals(request,ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
            val commands=s.encode(request)
            assertEquals(listOf(0xFC,type,2,32,1,0,33,16,1,0,35),commands.first().map{it.toInt()and 255})
            assertEquals(listOf(0xFA,type),commands.last().map{it.toInt()and 255})
            assertEquals(before,s.state)
            s.accept(b(0xFD,type,2,32,1,0,32,16,1,0,36))
            assertTrue(s.encode(request).isEmpty())
            assertFalse(s.encode(request(s)).isEmpty())
            s.accept(b(0xF5,type,2,1,0));assertTrue(s.encode(request(s)).isEmpty())
        }
    }
    @Test fun presetIndexedMappingsCanBeReorderedAndIncludeInactivePresets() {
        val s=ready(3)
        val blocks=listOf(16,1,0,35, 0,1,0,1, 32,1,0,32, 33,1,0,33,
            34,1,0,32, 48,1,0,48, 49,1,0,50)
        s.accept((listOf(0xFB,3,7)+blocks).map(Int::toByte).toByteArray())
        assertTrue(s.state.completeMappings)
        val command=s.encode(request(s)).first().map{it.toInt()and 255}
        val expected=blocks.toMutableList().apply{this[11]=33}
        assertEquals(listOf(0xFC,3,7)+expected,command)
        assertEquals(35,s.state.mappings!!.first().actions.single().function)
    }
    @Test fun sharedPresetsRequireEveryActiveKeyToPermitTheChange() {
        val action=SonyAssignableAction(0,32,listOf(32,33))
        val key=SonyAssignableKey(0,0,listOf(32),mapOf(32 to listOf(action)))
        val def=SonyAssignableDefinition(emptyList(),listOf(key,key.copy(id=1)),2,3)
        val mapping=listOf(SonyAssignableMapping(32,listOf(SonyAssignablePair(0,32))))
        val state=SonyAssignableFeatureState(def,listOf(32,32),listOf(true,true),mapping)
        val request=SonyControlRequest.SetAssignableAction(def,state.current!!,mapping,0,0,33)
        assertTrue(state.completeMappings);assertTrue(state.supports(request))
        assertFalse(state.copy(available=listOf(true,false)).supports(request))
        val restricted=def.copy(keys=listOf(key,key.copy(id=1,actions=mapOf(32 to listOf(action.copy(functions=listOf(32)))))))
        assertFalse(state.copy(definition=restricted).supports(request.copy(expected=restricted)))
    }
    @Test fun revocationAndInquiryChangeCannotReuseOldEvidence() {
        val s=ready(3);s.accept(b(0xFB,3,2,32,1,0,32,16,1,0,35))
        val request=request(s)
        s.accept(b(0xFB,3,2,32,1,0,32)) // malformed preserves
        assertFalse(s.encode(request).isEmpty())
        s.accept(b(0xFB,3,1,32,1,0,32)) // valid partial revokes
        assertTrue(s.encode(request).isEmpty())
        s.accept(b(0xFB,3,2,32,1,0,32,16,1,0,35))
        s.accept(capability(3).copyOf().apply{this[lastIndex]=32}) // valid changed capability
        assertNull(s.state.current);assertNull(s.state.mappings)
        s.accept(b(7,0,1,0xFE,0));assertNull(s.state.definition)
        assertNull(s.accept(capability(3)))
        s.accept(capability(14));assertEquals(14,s.state.definition!!.inquiredType)
        assertTrue(s.encode(request).isEmpty())
        s.accept(b(0xF1,14,1,0));assertFalse(s.state.hasSettings)
        s.accept(b(7,0,0));assertNull(s.state.definition)
    }
    @Test fun presetChangesInvalidateMappingsAndV1NeverEmitsActionCommands() {
        val s=ready(3);s.accept(b(0xFB,3,2,32,1,0,32,16,1,0,35))
        val request=request(s)
        s.accept(b(0xF9,3,2,254,16));assertFalse(s.state.hasSettings);assertNull(s.state.mappings)
        assertTrue(s.encode(request).isEmpty())
        val v1=SonyAssignableSession(false);assertTrue(v1.encode(request).isEmpty())
        val daily=SonyDailyControlsSession(true)
        assertTrue(daily.accept(b(7,0,1,0xFE,0))!!.queries.any{it.contentEquals(b(0xF0,14))})
    }
    @Test fun actualV2AdapterRejectsTableTwoAndResetsActionContract() {
        val adapter=requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(deviceName="WH-1000XM4",standardHeadset=true)))
        adapter.beginHandshake()
        val t1=dev.hyperears.protocol.sony.SonyHeadphonesWireCodec.MessageType.COMMAND_1
        val t2=dev.hyperears.protocol.sony.SonyHeadphonesWireCodec.MessageType.COMMAND_2
        fun report(type:dev.hyperears.protocol.sony.SonyHeadphonesWireCodec.MessageType,p:ByteArray)=adapter.receive(dev.hyperears.protocol.sony.SonyHeadphonesWireCodec.encode(type,0,p))
        report(t1,b(1,0,3,0,0,0,0,0))
        report(t2,b(7,0,1,0xF3,0));report(t2,capability(3))
        assertNull(adapter.runtimeState().features.get<SonyAssignableFeatureState>())
        report(t1,b(7,0,1,0xF3,0));report(t1,capability(3));report(t1,b(0xF7,3,2,32,16));report(t1,b(0xF3,3,2,0,0));report(t1,b(0xFB,3,2,32,1,0,32,16,1,0,35))
        val state=adapter.runtimeState().features.get<SonyAssignableFeatureState>()!!
        val request=SonyControlRequest.SetAssignableAction(state.definition!!,state.current!!,state.mappings!!,0,0,33)
        assertTrue(adapter.supportsControl(request));assertFalse(adapter.executeControl(request).accepted)
        adapter.resetProtocolSession();assertFalse(adapter.supportsControl(request))
        assertNull(adapter.runtimeState().features.get<SonyAssignableFeatureState>())
    }

}
