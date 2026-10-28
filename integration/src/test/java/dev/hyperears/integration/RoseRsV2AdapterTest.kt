package dev.hyperears.integration

import dev.hyperears.protocol.rose.RoseRsV2IdentityCodec as Identity
import org.junit.Assert.*
import org.junit.Test

class RoseRsV2AdapterTest {
    @Test fun automaticManufacturerSelectionKeepsVariantsDistinctAndNoStaticFeatures() {
        Identity.Model.entries.forEach { model ->
            val identity=identity(model)
            val resolved=EarbudAdapterRegistry.resolve(identity)!!
            assertEquals(model.id,resolved.id)
            assertNull(resolved.miLinkCardPresentationId)
            assertFalse(resolved.supportsControl(RoseRsV2ControlRequest.SetEqualizer(0)))
            assertFalse(RoseRsV2Adapter(model).matches(identity.copy(manufacturerData=emptyMap())))
            assertFalse(RoseRsV2Adapter(model).matches(identity.copy(nativeSystemEarbud=true)))
        }
    }
    @Test fun reportQualifiedRequestsReadBackWithoutOptimisticPublication() {
        Identity.Model.entries.forEach { model ->
            val a=RoseRsV2Adapter(model)
            assertFalse(a.executeControl(RoseRsV2ControlRequest.SetGame(true)).accepted)
            a.receive(frame(0x0E,0));assertTrue(a.executeControl(RoseRsV2ControlRequest.SetGame(true)).accepted)
            assertEquals(false,a.runtimeState().features.get<RoseRsV2SettingsFeatureState>()!!.game)
            a.receive(frame(0x0E,1));assertEquals(true,a.runtimeState().features.get<RoseRsV2SettingsFeatureState>()!!.game)
            a.resetProtocolSession();assertFalse(a.executeControl(RoseRsV2ControlRequest.SetGame(true)).accepted)
        }
    }
    @Test fun eqLabelsUseSourceSemanticValuesAndFindReceiveOnlyCodesAreNotWrites() {
        val a=RoseRsV2Adapter(Identity.Model.ASTRO_X);a.receive(frame(0x2A,0))
        val pop=a.executeControl(RoseRsV2ControlRequest.SetEqualizer(0))
        assertArrayEquals(byteArrayOf(0x2A,1),payload(pop.commands.single()))
        assertArrayEquals(byteArrayOf(0xFA.toByte(),0x2A),payload(pop.readback.single()))
        assertEquals(1,a.runtimeState().features.get<RoseRsV2SettingsFeatureState>()!!.eq)
        assertArrayEquals(byteArrayOf(0x2A,2),payload(a.executeControl(RoseRsV2ControlRequest.SetEqualizer(2)).commands.single()))
        a.receive(frame(0x2F,4));assertFalse(a.executeControl(RoseRsV2ControlRequest.Find(5)).accepted)
        assertTrue(a.executeControl(RoseRsV2ControlRequest.Find(1)).accepted)
        val encoded=ControlRequestTransport.encode(RoseRsV2ControlRequest.SetTouch(17,3))
        assertEquals(RoseRsV2ControlRequest.SetTouch(17,3),ControlRequestTransport.decode(encoded))
    }
    @Test fun sourceDistinctNoiseTouchAndAudioProfilesRejectWrongDialect() {
        val speak=RoseRsV2Adapter(Identity.Model.SPEAKFEEL2);speak.receive(frame(7,1));speak.receive(frame(9,1))
        assertFalse(speak.executeControl(RoseRsV2ControlRequest.SetTouchEnabled(true)).accepted)
        assertFalse(speak.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))
        val open=RoseRsV2Adapter(Identity.Model.OPENFEEL_L);open.receive(frame(0x2B,0))
        assertTrue(open.executeControl(RoseRsV2ControlRequest.SetAudio(1)).accepted)
        assertFalse(open.executeControl(RoseRsV2ControlRequest.SetAudio(2)).accepted)
        val ceramics=RoseRsV2Adapter(Identity.Model.CERAMICS_MK2_5736);ceramics.receive(frame(0x2B,0));ceramics.receive(frame(9,4))
        assertFalse(ceramics.executeControl(RoseRsV2ControlRequest.SetAudio(0)).accepted)
        assertTrue(ceramics.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.WIND)))
    }
    private fun identity(model:Identity.Model):EarbudIdentity {
        val bytes=model.prefix.chunked(2).map{it.toInt(16)}
        return EarbudIdentity("Renamed headset",true,manufacturerData=mapOf((bytes[0] or (bytes[1] shl 8)) to bytes.drop(2)))
    }
    private fun payload(bytes:ByteArray)=bytes.copyOfRange(3,bytes.size-2)
    private fun frame(type:Int,vararg values:Int):ByteArray {
        val p=byteArrayOf(0xDD.toByte(),0,(values.size+1).toByte(),type.toByte())+values.map(Int::toByte)
        return p+byteArrayOf(p.sumOf{it.toInt() and 255}.toByte(),0xAA.toByte())
    }
}
