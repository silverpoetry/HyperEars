package dev.hyperears.integration

import org.junit.Assert.*
import org.junit.Test

class OnePlusEarbudAdapterTest {
    private fun identity(name: String, standard: Boolean = true, native: Boolean = false) =
        EarbudIdentity(deviceName = name, standardHeadset = standard, nativeSystemEarbud = native)

    @Test
    fun exactBuds4PrecedesTheSharedFamilyAndKeepsTheBrandCatalogSeparate() {
        for (name in listOf("OnePlus Buds 4", "ONEPLUS BUDS4", "一加 Buds 4")) {
            assertEquals(OnePlusBuds4Adapter.ID, EarbudAdapterRegistry.resolve(identity(name))?.id)
        }
        for (name in listOf("OnePlus Buds", "OnePlus Buds Pro", "OnePlus Buds Pro 3",
            "OnePlus Buds 3 Pro", "OnePlus Buds 3", "OnePlus Buds 5", "一加 Buds Pro 2")) {
            assertEquals(name, OnePlusEarbudAdapter.ID, EarbudAdapterRegistry.resolve(identity(name))?.id)
        }
        assertEquals(setOf(OnePlusBuds4Adapter.ID, OnePlusEarbudAdapter.ID),
            EarbudAdapterRegistry.groups.single { it.id == "oneplus" }.adapters.map { it.id }.toSet())
        assertEquals(OppoEncoAir2ProAdapter.ID, EarbudAdapterRegistry.resolve(identity("OPPO Enco Air2 Pro"))?.id)
    }

    @Test
    fun nameAndHeadsetIdentitySelectCandidatesWithoutUsingTheSharedUuidAsABrand() {
        val family = OnePlusEarbudAdapter()
        for (name in listOf("OnePlus Nord Buds 4 Pro", "OnePlus Buds Z2", "OnePlus Buds Ace 2",
            "OnePlus Bullets Wireless", "OnePlus 14", "Buds 4", "OnePlus Buds 4-LEA", "OnePlus Buds 4 Case")) {
            assertFalse(name, family.matches(identity(name)))
        }
        assertFalse(family.matches(identity("OnePlus Buds 4", standard = false)))
        assertFalse(family.matches(identity("OnePlus Buds 4", native = true)))
        val unnamed = identity("Unknown headset").copy(serviceUuids = setOf(OpoProtocolAdapter.RFCOMM_UUID))
        assertEquals(StandardEarbudAdapter.ID, EarbudAdapterRegistry.resolve(unnamed)?.id)
    }

    @Test
    fun disablingExactAndFamilyEntriesUsesTheExistingSelectionRules() {
        val device = identity("OnePlus Buds 4")
        assertEquals(OnePlusEarbudAdapter.ID,
            EarbudAdapterRegistry.resolve(device, setOf(OnePlusBuds4Adapter.ID))?.id)
        assertEquals(OnePlusBuds4Adapter.ID,
            EarbudAdapterRegistry.resolve(device, setOf(OnePlusEarbudAdapter.ID))?.id)
        assertEquals(StandardEarbudAdapter.ID,
            EarbudAdapterRegistry.resolve(device, setOf(OnePlusBuds4Adapter.ID, OnePlusEarbudAdapter.ID))?.id)
    }

    @Test
    fun onlyAcceptedTelemetryUnlocksPrivateCapabilitiesAndSessionsRemainIndependent() {
        val exact = OnePlusBuds4Adapter()
        val family = OnePlusEarbudAdapter()
        assertNotSame(exact.protocolSession, family.protocolSession)
        for (adapter in listOf(exact, family)) {
            adapter.onSystemBatteryChanged(70)
            assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
            assertEquals(70, adapter.runtimeState().battery.overall.percent)
            assertTrue(adapter.snapshot().supportedNoiseModes.isEmpty())
            assertFalse(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))
            assertEquals(OppoEarbudAdapter().transports, adapter.transports)
            assertEquals(OppoEarbudAdapter().controlApps, adapter.controlApps)
        }
        // Captured in issue #94. Notification support alone confirms no battery/noise capability.
        exact.receive(hex("AA 12 00 00 00 82 F0 0B 00 00 09 01 02 03 04 08 0B F1 F2 F3"))
        assertEquals(BatterySource.SYSTEM_AGGREGATE, exact.snapshot().batterySource)
        assertTrue(exact.snapshot().supportedNoiseModes.isEmpty())
        exact.receive(hex("AA 0F 00 00 06 81 F0 08 00 00 03 01 64 02 5A 03 01"))
        assertEquals(BatterySource.PRIVATE_PROTOCOL, exact.snapshot().batterySource)
        assertEquals(listOf(100, 90, 1), listOf(exact.runtimeState().battery.left.percent,
            exact.runtimeState().battery.right.percent, exact.runtimeState().battery.case.percent))
        assertTrue(exact.snapshot().supportedNoiseModes.isEmpty())
        assertEquals(BatterySource.SYSTEM_AGGREGATE, family.snapshot().batterySource)
    }

    @Test
    fun sharedOpoSessionKeepsTheVerifiedThreeStateCommandsAndReadback() {
        for (adapter in listOf(OnePlusBuds4Adapter(), OnePlusEarbudAdapter())) {
            // Minimal core-state fixture in the same response format as the Buds 4 capture.
            adapter.receive(hex("AA 0C 00 00 0C 81 F0 05 00 00 01 01 80 00"))
            assertEquals(setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
                adapter.snapshot().supportedNoiseModes)
            assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)
            for ((mode, value) in listOf(NoiseMode.OFF to "01", NoiseMode.ANC to "02", NoiseMode.TRANSPARENCY to "04")) {
                val request = StandardControlRequest.SetNoiseMode(mode)
                assertTrue(adapter.supportsControl(request))
                assertArrayEquals(hex("AA 0A 00 00 04 04 F0 03 00 01 01 $value"),
                    adapter.protocolSession.encode(request).single())
                assertArrayEquals(hex("AA 09 00 00 0C 01 F0 02 00 01 01"),
                    adapter.protocolSession.readback(request).single())
            }
            assertFalse(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.WIND)))
        }
    }
    private fun hex(value: String): ByteArray = value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
