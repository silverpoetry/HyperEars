package dev.hyperears.integration

import dev.hyperears.protocol.samsung.SamsungBudsCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SamsungBuds2ProAdapterTest {
    @Test
    fun exactNameAndServiceUuidResolveBeforeStandardFallback() {
        val byName = EarbudAdapterRegistry.resolve(EarbudIdentity("Galaxy Buds2 Pro (1234)", true))
        assertEquals(SamsungBuds2ProAdapter.ID, byName?.id)
        val byCustomizedName = EarbudAdapterRegistry.resolve(
            EarbudIdentity("尚振雨 的 Buds2 Pro", true,
                serviceUuids = setOf(SamsungBuds2ProAdapter.BUDS2_PRO_UUID)),
        )
        assertEquals(SamsungBuds2ProAdapter.ID, byCustomizedName?.id)
        val byService = EarbudAdapterRegistry.resolve(
            EarbudIdentity("SM-R510", true, serviceUuids = setOf(SamsungBuds2ProAdapter.BUDS2_PRO_UUID)),
        )
        assertEquals(SamsungBuds2ProAdapter.ID, byService?.id)
    }

    @Test
    fun noiseControlUnlocksAfterValidDeviceStatus() {
        val adapter = SamsungBuds2ProAdapter()
        assertFalse(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))
        assertEquals(null, adapter.miLinkCardPresentationId)

        val result = adapter.receive(extendedStatus())
        assertEquals(HandshakeResult.Ready, result.handshake)
        assertTrue(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))
        assertEquals(100, adapter.runtimeState().battery.left.percent)
    }

    @Test
    fun noiseCommandUsesSamsungMessageIdAndDeviceConfirmation() {
        val adapter = SamsungBuds2ProAdapter()
        adapter.receive(extendedStatus())
        val noise = adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.TRANSPARENCY))
        assertTrue(noise.accepted)
        assertFalse(noise.stateChanged)
        assertEquals(NoiseMode.OFF, adapter.runtimeState().noiseMode)
        val noiseAck = adapter.receive(
            SamsungBudsCodec.packet(
                SamsungBudsCodec.UNIVERSAL_ACK,
                byteArrayOf(SamsungBudsCodec.NOISE_CONTROLS.toByte(), 2),
            ),
        )
        assertTrue(noiseAck.stateChanged)
        assertEquals(NoiseMode.TRANSPARENCY, adapter.runtimeState().noiseMode)
        assertEquals(SamsungBudsCodec.NOISE_CONTROLS, SamsungBudsCodec.parseFrame(noise.commands.single())?.id)
    }

    @Test
    fun knownSamsungModelsUseExactAdaptersAndRenamedSharedServiceUsesFamilyFallback() {
        val buds2 = EarbudAdapterRegistry.resolve(EarbudIdentity("Galaxy Buds2", true))!!
        assertEquals("samsung-galaxy-buds2", buds2.id)
        assertEquals(ControlAppCatalog.galaxyBuds2Manager, buds2.controlApps[1])
        val budsFe = EarbudAdapterRegistry.resolve(EarbudIdentity("Galaxy Buds FE", true))!!
        assertEquals("samsung-galaxy-buds-fe", budsFe.id)
        assertEquals(ControlAppCatalog.galaxyBudsFeManager, budsFe.controlApps[1])
        val renamed = EarbudAdapterRegistry.resolve(EarbudIdentity(
            "我的耳机", true, serviceUuids = setOf(SamsungBuds2ProAdapter.BUDS2_PRO_UUID),
        ))
        assertEquals("samsung-galaxy-buds-family", renamed?.id)
        assertEquals(listOf(ControlAppCatalog.galaxyWearable), renamed?.controlApps)
        renamed!!.receive(extendedStatus())
        assertTrue(renamed.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))
    }

    private fun extendedStatus(): ByteArray = hex(
        "FD 31 00 61 0D 04 64 64 01 00 33 58 00 01 BF 22 00 01 46 01 46 01 00 00 " +
            "03 35 00 03 00 10 01 01 01 01 32 03 01 01 01 00 0C AE 01 00 03 00 01 00 " +
            "01 01 B6 37 DD",
    )

    private fun hex(value: String): ByteArray = value.trim().split(Regex("\\s+"))
        .map { it.toInt(16).toByte() }.toByteArray()
}
