package dev.hyperears.integration

import dev.hyperears.protocol.oppo.OppoWireCodec
import org.junit.Assert.*
import org.junit.Test

class ForcedAdapterSelectionTest {
    private val onePlus = EarbudIdentity(
        deviceName = "OnePlus Buds 4", deviceAddress = "02:00:00:00:00:01", standardHeadset = true,
    )

    @Test
    fun compatibleOppoResponsesUnlockControlsAfterBypassingOnlyNameMatching() {
        val unregistered = onePlus.copy(deviceName = "Unregistered OPO headset")
        assertEquals(StandardEarbudAdapter.ID, EarbudAdapterRegistry.forIntegration(unregistered)?.id)
        val adapter = requireNotNull(EarbudAdapterRegistry.forIntegration(unregistered,
            forcedAdapterId = OppoEarbudAdapter.ID))
        assertFalse(adapter.matches(unregistered))
        assertEquals(OppoEarbudAdapter.ID, adapter.id)
        val request = StandardControlRequest.SetNoiseMode(NoiseMode.ANC)
        assertFalse(adapter.executeControl(request).accepted)
        assertTrue(adapter.beginHandshake().commands.any { it.contentEquals(OppoWireCodec.queryAnc) })

        val handshake = adapter.receive(OppoWireCodec.packet(OppoWireCodec.NOTIFICATION_SUPPORT_RESPONSE,
            payload = byteArrayOf(0, 1, 2)))
        assertEquals(HandshakeResult.Ready, handshake.handshake)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        adapter.receive(OppoWireCodec.packet(OppoWireCodec.BATTERY_RESPONSE,
            payload = byteArrayOf(1, 75, 2, 74, 3, 66)))
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.snapshot().batterySource)
        assertFalse(adapter.executeControl(request).accepted)
        val result = adapter.receive(OppoWireCodec.packet(OppoWireCodec.ANC_RESPONSE,
            payload = byteArrayOf(0, 1, 1, 0, 8)))
        assertTrue(result.stateChanged)
        assertTrue(adapter.snapshot().capabilities.noiseControl)
        assertArrayEquals(OppoWireCodec.setAnc(primary = 2), adapter.executeControl(request).commands.single())
    }

    @Test
    fun explicitOverrideStillWinsWhenTheDeviceGainsAutomaticSupport() {
        assertEquals(OnePlusBuds4Adapter.ID, EarbudAdapterRegistry.forIntegration(onePlus)?.id)
        val forced = requireNotNull(EarbudAdapterRegistry.forIntegration(onePlus,
            forcedAdapterId = OppoEarbudAdapter.ID))
        assertEquals(OppoEarbudAdapter.ID, forced.id)
        assertFalse(forced.matches(onePlus))
        assertFalse(forced.snapshot().capabilities.noiseControl)
        assertEquals(OnePlusBuds4Adapter.ID, EarbudAdapterRegistry.forIntegration(onePlus)?.id)
    }

    @Test
    fun incompatibleBytesDoNotGrantPrivateControls() {
        val adapter = requireNotNull(EarbudAdapterRegistry.forIntegration(onePlus,
            forcedAdapterId = OppoEarbudAdapter.ID))
        adapter.beginHandshake()
        adapter.receive(byteArrayOf(0x13, 0x37, 0x00, 0x01))
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        assertFalse(adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)).accepted)
    }

    @Test
    fun forcedSelectionPreservesPlatformProtectionAndDisabledFallback() {
        assertNull(EarbudAdapterRegistry.forIntegration(onePlus.copy(nativeSystemEarbud = true),
            forcedAdapterId = OppoEarbudAdapter.ID))
        assertNull(EarbudAdapterRegistry.forIntegration(onePlus.copy(deviceName = "AirPods Pro"),
            forcedAdapterId = OppoEarbudAdapter.ID))
        assertNull(EarbudAdapterRegistry.forIntegration(onePlus.copy(standardHeadset = false),
            forcedAdapterId = OppoEarbudAdapter.ID))
        assertEquals(StandardEarbudAdapter.ID, EarbudAdapterRegistry.forIntegration(onePlus,
            disabledAdapterIds = setOf(OppoEarbudAdapter.ID), forcedAdapterId = OppoEarbudAdapter.ID)?.id)
        assertNull(EarbudAdapterRegistry.forIntegration(onePlus,
            disabledAdapterIds = setOf(OppoEarbudAdapter.ID, StandardEarbudAdapter.ID),
            forcedAdapterId = OppoEarbudAdapter.ID))
        assertEquals(StandardEarbudAdapter.ID, EarbudAdapterRegistry.forIntegration(onePlus,
            forcedAdapterId = "removed-adapter")?.id)
    }

    @Test
    fun allOfferedEntriesStartLockedAndUseIndependentInstances() {
        EarbudAdapterRegistry.probeGroups.flatMap { it.adapters }.forEach { entry ->
            val first = requireNotNull(EarbudAdapterRegistry.forIntegration(onePlus, forcedAdapterId = entry.id))
            val second = requireNotNull(EarbudAdapterRegistry.forIntegration(onePlus, forcedAdapterId = entry.id))
            assertEquals(entry.id, first.id)
            assertNotSame(first, second)
            if (first.privateProtocolRequired) {
                assertFalse(entry.id, first.snapshot().capabilities.noiseControl)
                assertEquals(entry.id, BatterySource.SYSTEM_AGGREGATE, first.snapshot().batterySource)
                assertNull(entry.id, first.snapshot().presentationId)
            }
        }
        assertEquals(StandardEarbudAdapter.ID, EarbudAdapterRegistry.forIntegration(onePlus,
            forcedAdapterId = BoseQuietComfortHeadphonesAdapter().id)?.id)
    }
}
