package dev.hyperears.integration

import org.junit.Assert.*
import org.junit.Test

class RoseModelIdentityTest {
    @Test fun i7RequiresItsOwnFourModeReportAndResetsEvidence() {
        val adapter = RoseEarfeelI7Adapter()
        fun noise(value: Int): ByteArray {
            val body = byteArrayOf(0xDD.toByte(), 0, 2, 9, value.toByte())
            return body + byteArrayOf(body.sumOf { it.toInt() and 255 }.toByte(), 0xAA.toByte())
        }
        adapter.receive(noise(5))
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        adapter.receive(noise(4))
        assertEquals(NoiseMode.WIND, adapter.runtimeState().noiseMode)
        assertTrue(adapter.snapshot().capabilities.noiseControl)
        adapter.receive(noise(6))
        assertEquals(NoiseMode.WIND, adapter.runtimeState().noiseMode)
        assertEquals(RoseBudsFeelProtocolFamilyAdapter.PRESENTATION_ID, adapter.miLinkCardPresentationId)
        adapter.resetProtocolSession()
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertNull(adapter.miLinkCardPresentationId)
    }
    @Test fun exactModelsUseDistinctIdentitiesAndRfcommWithoutStaticCapabilities() {
        mapOf("EarFeel i7" to "rose-earfeel-i7", "ROSE EARFEEL i7" to "rose-earfeel-i7",
            "ROSE Ceramics U" to "rose-ceramics-u").forEach { (name, id) ->
            val adapter = EarbudAdapterRegistry.resolve(EarbudIdentity(deviceName = name, standardHeadset = true))!!
            assertEquals(id, adapter.id)
            assertEquals(AdapterResolution.EXACT_MATCH, adapter.resolution)
            assertEquals(RoseBudsFeelProtocolFamilyAdapter.DATA_CHANNEL_UUID,
                (adapter.transports.single() as RfcommEndpointSpec.ServiceUuid).uuid)
            assertFalse(adapter.snapshot().capabilities.noiseControl)
            assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        }
        assertEquals(RoseLuliUltraAdapter.ID, EarbudAdapterRegistry.resolve(
            EarbudIdentity(deviceName = "ROSE Ceramics Ultra", standardHeadset = true))!!.id)
    }

    @Test fun nativeAndSuffixModelsDoNotAcquireExactProfile() {
        for (name in listOf("EarFeel i7", "ROSE Ceramics U")) {
            val native = EarbudAdapterRegistry.resolve(EarbudIdentity(
                deviceName = name, standardHeadset = true, nativeSystemEarbud = true))
            assertFalse(native?.id in setOf("rose-earfeel-i7", "rose-ceramics-u"))
            val suffix = EarbudAdapterRegistry.resolve(EarbudIdentity(
                deviceName = "$name Pro", standardHeadset = true))
            assertFalse(suffix?.id in setOf("rose-earfeel-i7", "rose-ceramics-u"))
        }
    }
}
