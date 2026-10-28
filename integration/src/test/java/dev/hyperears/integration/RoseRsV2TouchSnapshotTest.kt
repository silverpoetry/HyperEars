package dev.hyperears.integration
import dev.hyperears.protocol.rose.RoseRsV2IdentityCodec.Model
import org.junit.Assert.*
import org.junit.Test
class RoseRsV2TouchSnapshotTest {
    // Explicit pair order intentionally differs from the canonical UI order.
    private val raw=listOf(20,200,1,0,2,1,3,2,4,3,17,4,18,5,19,6)
    private fun frame(pairs:List<Int>):ByteArray {
        val body=(listOf(0xdd,0,pairs.size+1,1)+pairs).map(Int::toByte).toByteArray()
        return body+byteArrayOf(body.sumOf{it.toInt()and 255}.toByte(),0xaa.toByte())
    }
    @Test fun fullRawMappingAndModelPinRejectSiblingChangesAndReset() {
        val adapter=RoseRsV2Adapter(Model.KY01)
        adapter.receive("DD 00 11 01 14 C8 01 00 02 01 03 02 04 03 11 04 12 05 13 06 20 AA".split(" ").map{it.toInt(16).toByte()}.toByteArray())
        val feature=adapter.runtimeState().features.get<RoseRsV2SettingsFeatureState>()!!
        assertEquals(listOf(0,1,2,3,4,5,6,200),feature.touch)
        assertEquals(raw,feature.touchRaw)
        val request=RoseRsV2ControlRequest.SetTouch(1,8,Model.KY01.name,raw)
        assertTrue(adapter.executeControl(request).accepted)
        assertEquals(request,ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
        assertFalse(adapter.supportsControl(request.copy(expectedModel=Model.ASTRO_X.name)))
        assertFalse(adapter.supportsControl(RoseRsV2ControlRequest.SetTouch(1,8)))
        adapter.receive(frame(raw.toMutableList().apply{this[1]=201}))
        assertFalse(adapter.supportsControl(request))
        adapter.resetProtocolSession();assertFalse(adapter.supportsControl(request))
    }
    @Test fun qualifiedOldIdsUseTheSamePinnedContractWithoutNameInheritance() {
        for(model in listOf(Model.BUDSFEEL_MK2_SOURCE,Model.EARFEEL_I7_SOURCE,Model.CERAMICS_U_SOURCE,Model.FURINA_SOURCE)) {
            val prefix=model.prefix.chunked(2).map{it.toInt(16)}
            val adapter=EarbudAdapterRegistry.resolve(EarbudIdentity("renamed",true,manufacturerData=mapOf((prefix[0] or (prefix[1] shl 8)) to prefix.drop(2))))!!
            val request=RoseRsV2ControlRequest.SetTouch(1,8,model.name,raw)
            assertFalse(adapter.supportsControl(request))
            adapter.receive(frame(raw));assertTrue(adapter.executeControl(request).accepted)
            assertEquals(model.existingAdapterId,adapter.id)
            adapter.resetProtocolSession();assertFalse(adapter.supportsControl(request))
        }
    }
    @Test fun sourceActionMembershipAndDuplicatePartialReports() {
        val session=RoseRsV2Session(Model.ASTRO_X);session.offer(frame(raw))
        assertTrue(session.encode(RoseRsV2ControlRequest.SetTouch(1,8,Model.ASTRO_X.name,raw)).isEmpty())
        assertFalse(session.encode(RoseRsV2ControlRequest.SetTouch(1,7,Model.ASTRO_X.name,raw)).isEmpty())
        for(model in listOf(Model.MOKA,Model.SPEAKFEEL,Model.MAGICIAN)) {
            val adapter=RoseRsV2Adapter(model);adapter.receive(frame(raw))
            assertNull(adapter.runtimeState().features.get<RoseRsV2SettingsFeatureState>()?.touch)
        }
        val adapter=RoseRsV2Adapter(Model.KY01)
        adapter.receive(frame(raw.dropLast(2)))
        adapter.receive(frame(raw.toMutableList().apply{this[0]=1}))
        assertNull(adapter.runtimeState().features.get<RoseRsV2SettingsFeatureState>()?.touch)
    }
}
