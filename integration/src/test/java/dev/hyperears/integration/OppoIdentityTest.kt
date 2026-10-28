package dev.hyperears.integration

import dev.hyperears.protocol.oppo.OppoWireCodec
import org.junit.Assert.*
import org.junit.Test

class OppoIdentityTest {
    private fun resolve(name: String): EarbudAdapter = requireNotNull(EarbudAdapterRegistry.resolve(
        EarbudIdentity(deviceName = name, standardHeadset = true),
    ))
    private fun product(id: Int) = OppoWireCodec.packet(0x8103,
        payload = byteArrayOf(0, id.toByte(), (id ushr 8).toByte(), (id ushr 16).toByte()))
    private fun anc(value: Int) = OppoWireCodec.packet(OppoWireCodec.ANC_RESPONSE,
        payload = byteArrayOf(0, 1, 1, value.toByte()))
    private fun replacement(adapter: EarbudAdapter, bytes: ByteArray): EarbudAdapter =
        (adapter.receive(bytes).handshake as HandshakeResult.Replace).adapter

    @Test fun suffixModelsAreExactCandidatesWithoutCapabilities() {
        for (name in listOf("OPPO Enco X3s", "OPPO Enco Air5 Pro", "OPPO Enco Air5s", "OnePlus Buds Pro 4", "realme Buds Air8")) {
            val adapter = resolve(name)
            assertEquals(name, adapter.displayName)
            assertEquals(AdapterResolution.EXACT_MATCH, adapter.resolution)
            assertFalse(adapter.snapshot().capabilities.noiseControl)
            assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.effectiveBatterySource())
        }
        assertFalse(resolve("OPPO Enco X3s") is OppoEncoX3Adapter)
        assertFalse(resolve("OPPO Enco Air5 Pro") is OppoEncoAir5Adapter)
        assertTrue(resolve("OPPO Enco X3 custom") !is OppoEncoX3Adapter)
    }
    @Test fun childAliasesAndAllCatalogRowsAreRetained() {
        assertEquals(141, OppoProductCatalog.products.size)
        assertEquals(118, OppoProductCatalog.products.count { it.privateProbeCandidate })
        assertEquals("OPPO Enco X4", resolve("OPPO Enco Bohr").displayName)
        assertEquals("OnePlus Buds Clip", resolve("OnePlus Open Nobel").displayName)
        assertEquals("OPPO Enco X3", resolve("enco x3").displayName)
        assertEquals("OPPO Enco Buds3 Pro+", resolve("oppo enco buds3 pro+").displayName)
        assertEquals("OPPO Enco Buds3 Pro", resolve("oppo enco buds3 pro").displayName)
        assertEquals(AdapterResolution.FAMILY_MATCH, resolve("OnePlus Buds").resolution)
    }
    @Test fun productQueryPromotesIdentityButNotCapabilities() {
        val original = resolve("OPPO Future")
        assertTrue(original.beginHandshake().commands.any { it.contentEquals(OppoWireCodec.packet(0x0103)) })
        val promoted = replacement(original, product(0x067814))
        assertEquals("OnePlus Buds Pro 4", promoted.displayName)
        assertEquals(AdapterResolution.PROTOCOL_CONFIRMED, promoted.resolution)
        assertSame(original.protocolSession, promoted.protocolSession)
        assertFalse(promoted.snapshot().capabilities.noiseControl)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, promoted.effectiveBatterySource())
    }
    @Test fun productRefinesWireVariantBeforeCoalescedReportAndResetClearsEvidence() {
        val original = resolve("OPPO Future")
        val promoted = replacement(original, product(0x063810) + anc(1))
        assertEquals("OPPO Enco Air2 Pro", promoted.displayName)
        assertEquals(NoiseMode.ANC, promoted.runtimeState().noiseMode)
        assertTrue(promoted.snapshot().capabilities.noiseControl)
        val command = promoted.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.OFF))
        assertTrue(command.accepted)
        assertArrayEquals(OppoWireCodec.setAnc(2), command.commands.single())
        assertArrayEquals(OppoWireCodec.queryAnc, command.readback.single())
        promoted.resetProtocolSession()
        assertEquals(AdapterResolution.EXACT_MATCH, promoted.resolution)
        assertFalse(promoted.snapshot().capabilities.noiseControl)
        assertNull(promoted.runtimeState().noiseMode)
        promoted.receive(anc(1))
        assertEquals(NoiseMode.ANC, promoted.runtimeState().noiseMode)
    }
    @Test fun invalidUnknownAndDisabledProductsDoNotSelectConcreteIdentity() {
        val original = resolve("OPPO Future")
        assertFalse(original.receive(product(0x123456)).handshake is HandshakeResult.Replace)
        assertFalse(original.receive(OppoWireCodec.packet(0x8103, payload = byteArrayOf(1, 0x10, 0x74, 6))).handshake is HandshakeResult.Replace)
        original.receive(anc(2))
        original.configureDisabledAdapterIds(setOf(OppoEncoAir2ProAdapter.ID))
        assertFalse(original.receive(product(0x063810)).handshake is HandshakeResult.Replace)
        assertEquals(NoiseMode.ANC, original.runtimeState().noiseMode)
        assertArrayEquals(OppoWireCodec.setAnc(1), original.executeControl(
            StandardControlRequest.SetNoiseMode(NoiseMode.OFF)).commands.single())

        // Policy is checked on the current aggregate after a successful in-place refinement.
        original.configureDisabledAdapterIds(emptySet())
        val promoted = replacement(original, product(0x067410))
        promoted.configureDisabledAdapterIds(setOf(OppoEncoAir2ProAdapter.ID))
        assertFalse(promoted.receive(product(0x063810)).handshake is HandshakeResult.Replace)
        assertArrayEquals(OppoWireCodec.setAnc(1), promoted.executeControl(
            StandardControlRequest.SetNoiseMode(NoiseMode.OFF)).commands.single())
    }
    @Test fun nativeHeadsetsAndSpeakerCatalogRowsAreNotClaimed() {
        for (name in listOf("OPPO Enco X4", "OnePlus Buds Clip", "realme Buds Air8")) {
            assertFalse(OppoEarbudAdapter().matches(EarbudIdentity(name, true, nativeSystemEarbud = true)))
        }
        assertFalse(resolve("realme Cobble Speaker") is OppoEarbudAdapter)
    }
    @Test fun priorReportSurvivesSameDialectPromotionButVariantChangeNeedsNewEvidence() {
        val family = resolve("OPPO Future")
        family.receive(anc(2))
        val x3 = replacement(family, product(0x067410))
        assertTrue(x3.snapshot().capabilities.noiseControl)
        val air2 = replacement(x3, product(0x063810))
        assertFalse(air2.snapshot().capabilities.noiseControl)
        assertNull(air2.runtimeState().noiseMode)
        air2.receive(anc(1))
        assertEquals(NoiseMode.ANC, air2.runtimeState().noiseMode)
    }
    @Test fun catalogFlagsNeverEnableControlsAndEveryUniqueCandidateNameRoutes() {
        for (product in OppoProductCatalog.products.filter { it.privateProbeCandidate }) {
            for (alias in product.aliases.filter { OppoProductCatalog.exact(it) != null }) {
                val adapter = resolve(alias)
                assertEquals(alias, product.adapterId, adapter.id)
                assertFalse(alias, adapter.snapshot().capabilities.noiseControl)
                assertNull(adapter.snapshot().presentationId)
            }
        }
    }
    @Test fun coalescedBatteryRemainsReportConfirmedAcrossIdentityReplacement() {
        val family = resolve("OPPO Future")
        val battery = OppoWireCodec.packet(OppoWireCodec.BATTERY_RESPONSE,
            payload = byteArrayOf(1, 90, 2, 80))
        val promoted = replacement(family, battery + product(0x067410))
        assertEquals(BatterySource.PRIVATE_PROTOCOL, promoted.effectiveBatterySource())
        assertEquals(90, promoted.runtimeState().battery.left?.percent)
        assertFalse(promoted.snapshot().capabilities.noiseControl)
        promoted.resetProtocolSession()
        assertEquals(BatterySource.SYSTEM_AGGREGATE, promoted.effectiveBatterySource())
        assertNull(promoted.runtimeState().battery.left.percent)
        assertFalse(promoted.receive(product(0xABCDEF)).handshake is HandshakeResult.Replace)
        assertEquals("OPPO Enco X3", promoted.displayName)
    }
}
