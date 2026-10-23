package dev.hyperears.integration

import dev.hyperears.protocol.flos2.FloS2WireCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloS2EarbudAdapterTest {
    @Test
    fun registrySelectsExactOpenProS2BeforeTheFloS2SportFamily() {
        val exact = resolveRequired("OpenPro S2")
        val family = resolveRequired("Dreame Flo S2")

        assertTrue(exact is TheAstroOpenProS2Adapter)
        assertEquals(AdapterResolution.EXACT_MATCH, exact.resolution)
        assertTrue(family is FloS2SportGattAdapter)
        assertEquals(AdapterResolution.FAMILY_MATCH, family.resolution)
        assertEquals("FloS2 sport earbud family", family.displayName)
    }

    @Test
    fun familyMatchesBrandNamesAndThePrivateServiceUuid() {
        assertTrue(resolve("HaptiFit Terra") is FloS2SportGattAdapter)
        assertTrue(
            EarbudAdapterRegistry.resolve(
                EarbudIdentity(
                    deviceName = "Sport Earbud",
                    standardHeadset = true,
                    serviceUuids = setOf("0000ac00-0000-1000-8000-00805f9b34fb"),
                ),
            ) is FloS2SportGattAdapter,
        )
        val unrecognized = resolve("Flow S2")
        assertTrue(unrecognized is StandardEarbudAdapter)
        assertFalse(unrecognized is FloS2EarbudAdapter)
    }

    @Test
    fun nativeSystemDeviceNeverEntersTheFloS2Chain() {
        val adapter = EarbudAdapterRegistry.resolve(
            EarbudIdentity(
                deviceName = "OpenPro S2",
                standardHeadset = true,
                nativeSystemEarbud = true,
            ),
        )

        assertNull(adapter)
    }

    @Test
    fun privateCapabilitiesStayClosedUntilTheirOwnReadResponses() {
        val adapter = TheAstroOpenProS2Adapter()

        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        assertTrue(adapter.snapshot().capabilities.battery)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertEquals(
            listOf(FloS2WireCodec.queryAllStatus.toList()),
            adapter.beginHandshake().commands.map(ByteArray::toList),
        )

        val status = adapter.receive(STATUS_STANDARD.hexBytes())
        assertEquals(HandshakeResult.Ready, status.handshake)
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.snapshot().batterySource)
        assertEquals(70, adapter.runtimeState().battery.left.percent)
        assertEquals(
            FloS2SoundEffect.STANDARD,
            adapter.runtimeState().features.get<FloS2SoundEffectFeatureState>()?.effect,
        )
        assertFalse(adapter.snapshot().capabilities.noiseControl)
    }

    @Test
    fun batteryPushUpdatesTheAggregateReadingWithoutNewCapabilities() {
        val adapter = TheAstroOpenProS2Adapter()
        adapter.receive(STATUS_STANDARD.hexBytes())
        val before = adapter.snapshot()

        adapter.receive("AE 66 E0 02 3C 00 B0 11".hexBytes())

        assertEquals(60, adapter.runtimeState().battery.left.percent)
        assertEquals(before.capabilities, adapter.snapshot().capabilities)
    }

    @Test
    fun soundEffectControlIsGatedAndConfirmedByTheStatusReadback() {
        val adapter = TheAstroOpenProS2Adapter()
        val request = FloS2ControlRequest.SetSoundEffect(FloS2SoundEffect.ENHANCED)

        assertFalse(adapter.supportsControl(request))
        assertFalse(adapter.executeControl(request).accepted)

        adapter.receive(STATUS_STANDARD.hexBytes())
        val result = adapter.executeControl(request)

        assertTrue(result.accepted)
        assertFalse(result.stateChanged)
        assertEquals(
            listOf(FloS2WireCodec.setSoundEffect(FloS2WireCodec.SoundEffect.ENHANCED).toList()),
            result.commands.map(ByteArray::toList),
        )
        assertEquals(
            listOf(FloS2WireCodec.queryAllStatus.toList()),
            result.readback.map(ByteArray::toList),
        )
        assertEquals(
            FloS2SoundEffect.STANDARD,
            adapter.runtimeState().features.get<FloS2SoundEffectFeatureState>()?.effect,
        )

        adapter.receive(STATUS_ENHANCED.hexBytes())
        assertEquals(
            FloS2SoundEffect.ENHANCED,
            adapter.runtimeState().features.get<FloS2SoundEffectFeatureState>()?.effect,
        )
    }

    @Test
    fun protocolResetClearsTheVendorFeatureState() {
        val adapter = TheAstroOpenProS2Adapter()
        adapter.receive(STATUS_STANDARD.hexBytes())

        adapter.resetProtocolSession()

        assertNull(adapter.runtimeState().features.get<FloS2SoundEffectFeatureState>())
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
    }

    @Test
    fun soundEffectPresentationAppearsOnlyAfterProtocolEvidence() {
        val adapter = TheAstroOpenProS2Adapter()

        assertNull(adapter.miLinkCardPresentationId)

        adapter.receive(STATUS_STANDARD.hexBytes())
        assertEquals(
            FloS2MiLinkPresentationIds.SOUND_EFFECT,
            adapter.miLinkCardPresentationId,
        )

        adapter.resetProtocolSession()
        assertNull(adapter.miLinkCardPresentationId)
    }

    @Test
    fun failedPublicProtocolProbeFallsBackDirectlyToStandardIntegration() {
        val resolution = FloS2SportGattAdapter().resolveInitialProtocolFailure()

        assertTrue(resolution is InitialProtocolFailureResolution.FallbackTo)
        val fallback = (resolution as InitialProtocolFailureResolution.FallbackTo).adapter
        assertEquals(StandardEarbudAdapter.ID, fallback.id)
        assertFalse(fallback.privateProtocolRequired)
    }

    private fun resolve(name: String): EarbudAdapter? = EarbudAdapterRegistry.resolve(
        EarbudIdentity(
            deviceName = name,
            standardHeadset = true,
        ),
    )

    private fun resolveRequired(name: String): EarbudAdapter = requireNotNull(resolve(name))

    private companion object {
        /**
         * Full-status responses captured on 2026-10-24; MAC and serial are anonymized and the CRC
         * recomputed (see FloS2WireCodecTest). The only differing payload byte is the sound
         * effect: standard (0x00) versus enhanced (0x03).
         */
        val STATUS_STANDARD =
            "AE 66 FF 48 A3 8D DB 6A E0 01 31 2E 36 2E 36 31 2E 30 2E 30 32 2E 32 2E 36 FF FF FF FF " +
                "46 00 38 00 72 74 00 01 1A B4 3C 46 6C 6F 20 53 32 66 55 44 33 22 11 32 00 02 00 00 " +
                "01 01 45 30 32 30 30 33 54 45 53 54 30 30 30 30 30 31 00 3D 08"

        val STATUS_ENHANCED =
            "AE 66 FF 48 A3 8D DB 6A E0 01 31 2E 36 2E 36 31 2E 30 2E 30 32 2E 32 2E 36 FF FF FF FF " +
                "46 00 38 00 72 74 00 01 1A B4 3C 46 6C 6F 20 53 32 66 55 44 33 22 11 32 03 02 00 00 " +
                "01 01 45 30 32 30 30 33 54 45 53 54 30 30 30 30 30 31 00 20 A7"
    }

    private fun String.hexBytes(): ByteArray = trim()
        .split(Regex("\\s+"))
        .map { it.toInt(16).toByte() }
        .toByteArray()
}
