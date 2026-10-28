package dev.hyperears.integration

import dev.hyperears.protocol.oppo.OppoWireCodec as Wire
import org.junit.Assert.*
import org.junit.Test

class OppoNoiseSettingsTest {
    private fun packet(command: Int,vararg p: Int) = Wire.packet(command,payload=p.map(Int::toByte).toByteArray())
    private fun identity(id: Int) = packet(0x8103,0,id and 255,(id ushr 8) and 255,id ushr 16)
    private fun identified(id: Int): EarbudAdapter = (OppoEarbudAdapter().receive(identity(id)).handshake as HandshakeResult.Replace).adapter
    private fun report(index: Int) = packet(0x810C,0,1,1,(1 shl index) and 255,(1 shl index) ushr 8)
    private fun noise(device: EarbudAdapter) = device.runtimeState().features.get<OppoSettingsFeatureState>()!!.noise!!
    @Test fun namedIndexedCandidateWaitsForActualIdentityAndAcceptsDifferentProduct() {
        val named = requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(deviceName="OPPO Enco Air4s",standardHeadset=true)))
        named.receive(report(0))
        assertNull(named.runtimeState().noiseMode)
        assertFalse(named.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)).accepted)
        assertTrue(named.protocolSession.encode(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)).isEmpty())
        val actual = (named.receive(identity(0x065414)+report(5)).handshake as HandshakeResult.Replace).adapter
        assertEquals(0x065414,noise(actual).definition.productId)
        assertEquals(OppoNoiseMode.MEDIUM,noise(actual).selected!!.mode)
    }
    @Test fun levelOnlyModelNativeAncUsesRealLevelAndPreservesAlreadySelectedLevel() {
        val device = identified(0x061410);device.receive(report(0))
        fun write() = Wire.Decoder().offer(device.protocolSession.encode(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)).single()).single().payload
        assertArrayEquals(byteArrayOf(1,1,8),write()) // First source ANC option is Deep, slot3.
        device.receive(report(2))
        assertArrayEquals(byteArrayOf(1,1,4),write()) // Preserve confirmed Light rather than force Deep.
    }
    @Test fun identityAndCatalogAloneDoNotGrantLevelsButCurrentModeDoes() {
        assertEquals(43,OppoNoiseCatalog.size)
        val device = identified(0x065414)
        val definition = OppoNoiseCatalog.find(0x065414)!!
        assertFalse(device.executeControl(OppoControlRequest.SelectNoiseOption(definition,4)).accepted)
        device.receive(report(5))
        assertEquals(OppoNoiseMode.MEDIUM,noise(device).selected!!.mode)
        assertTrue(device.executeControl(OppoControlRequest.SelectNoiseOption(definition,11)).accepted)
        assertEquals(5,noise(device).reportedIndex)
        assertArrayEquals(byteArrayOf(1,1,0,8),Wire.Decoder().offer(device.executeControl(OppoControlRequest.SelectNoiseOption(definition,11)).commands.single()).single().payload)
    }
    @Test fun reportAliasesUseParentForPrimaryWritesAndConditionalListenIsReadOnly() {
        val device = identified(0x065414)
        device.receive(report(8))
        assertEquals(OppoNoiseMode.TRANSPARENCY,noise(device).selected!!.mode)
        assertFalse(device.executeControl(OppoControlRequest.SelectNoiseOption(noise(device).definition,8)).accepted)
        assertArrayEquals(byteArrayOf(1,1,4),Wire.Decoder().offer(device.protocolSession.encode(StandardControlRequest.SetNoiseMode(NoiseMode.TRANSPARENCY)).single()).single().payload)
        val air = identified(0x063810);air.receive(report(3))
        assertEquals(OppoNoiseMode.LISTEN,noise(air).selected!!.mode)
        assertFalse(air.executeControl(OppoControlRequest.SelectNoiseOption(noise(air).definition,3)).accepted)
        assertArrayEquals(byteArrayOf(1,1,1),Wire.Decoder().offer(air.protocolSession.encode(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)).single()).single().payload)
    }
    @Test fun gestureMaskEchoNeverChangesModeAndUnknownCurrentIndexRevokesSelector() {
        val device = identified(0x065414);device.receive(report(5))
        device.receive(packet(0x0204,3,2,1,7,8))
        assertEquals(5,noise(device).reportedIndex)
        device.receive(report(15))
        assertNull(device.runtimeState().features.get<OppoSettingsFeatureState>()!!.noise)
        assertTrue(device.protocolSession.encode(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)).isEmpty())
    }
    @Test fun productChangeRejectsOldDefinitionAndDoesNotReplayOldMode() {
        var device = identified(0x065414);device.receive(report(5))
        val old = OppoControlRequest.SelectNoiseOption(noise(device).definition,4)
        device = (device.receive(identity(0x063810)).handshake as HandshakeResult.Replace).adapter
        assertFalse(device.executeControl(old).accepted)
        assertNull(device.runtimeState().noiseMode)
        device.receive(report(0))
        assertFalse(device.executeControl(old).accepted)
        assertTrue(device.protocolSession.encode(old).isEmpty())
    }
    @Test fun typedSelectorRoundTripsAndDisabledProductDoesNotAdmitProfile() {
        val request = OppoControlRequest.SelectNoiseOption(OppoNoiseCatalog.find(0x065414)!!,4)
        assertEquals(request,ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
        val family = OppoEarbudAdapter()
        family.configureDisabledAdapterIds(setOf(OppoProductCatalog.find(0x065414)!!.adapterId))
        family.receive(identity(0x065414)+report(5))
        assertFalse(family.executeControl(request).accepted)
    }
}
