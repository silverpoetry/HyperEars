package dev.hyperears.integration

import dev.hyperears.protocol.swarmoo.SwarmooMo3WireCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SwarmooEarbudAdapterTest {
    @Test
    fun exactNamesSelectCandidateButSharedServiceCannotIdentifyBrand() {
        listOf("SWARMOO MO3", "SWARMOO MO3 PRO").forEach { name ->
            val adapter = EarbudAdapterRegistry.resolve(EarbudIdentity(name, true))!!
            assertEquals(SwarmooMo3ProAdapter.ID, adapter.id)
            assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
            assertTrue(adapter.snapshot().supportedNoiseModes.isEmpty())
            assertNull(adapter.snapshot().presentationId)
        }
        assertFalse(SwarmooMo3ProAdapter().matches(EarbudIdentity("SWARMOO MO3", true, true)))
        assertFalse(SwarmooMo3ProAdapter().matches(EarbudIdentity("SWARMOO MO3", false)))
        assertEquals("swarmoo-family", EarbudAdapterRegistry.resolve(EarbudIdentity("SWARMOO MO4", true))!!.id)
        val unknown = EarbudAdapterRegistry.resolve(EarbudIdentity("Other headset", true,
            serviceUuids = setOf(SwarmooMo3WireCodec.SERVICE_UUID)))!!
        assertFalse(unknown is SwarmooEarbudAdapter)
    }

    @Test
    fun routesUseOnlyDeclaredEndpointsAndMtuFitsCompleteState() {
        val adapter = SwarmooMo3ProAdapter()
        val transport = adapter.transports.single() as GattTransportSpec
        assertEquals(26, transport.minimumMtu)
        assertEquals(300L, transport.minimumWriteIntervalMs)
        assertEquals(SwarmooMo3WireCodec.SERVICE_UUID, transport.serviceUuid)
        assertEquals(transport.writeCharacteristicUuid, transport.notifyCharacteristicUuid)
        assertEquals(SwarmooMo3WireCodec.BATTERY_UUID, transport.additionalRoutes.single().writeCharacteristicUuid)
        assertEquals(listOf(null, "battery"), adapter.protocolSession.commandRoutes(SwarmooMo3WireCodec.query))
        assertEquals(listOf<String?>(null), adapter.protocolSession.commandRoutes(SwarmooMo3WireCodec.setMain(1)))
        assertThrows(IllegalArgumentException::class.java) {
            transport.copy(additionalRoutes = transport.additionalRoutes + transport.additionalRoutes)
        }
        assertThrows(IllegalArgumentException::class.java) { transport.copy(serviceUuid = null) }
        assertThrows(IllegalArgumentException::class.java) { transport.copy(minimumMtu = 22) }
        assertThrows(IllegalArgumentException::class.java) { transport.copy(minimumWriteIntervalMs = -1) }
    }

    @Test
    fun batteryAndModeUnlockIndependentlyAndWrongRoutesAreIgnored() {
        val adapter = SwarmooMo3ProAdapter()
        assertNull(adapter.receive(battery(100, 99, 20)).handshake)
        assertNull(adapter.receive(mode(1, 2), "battery").handshake)
        assertNull(adapter.receive(mode(1, 2).copyOf(20)).handshake)
        val result = adapter.receive(battery(100, 99, 20), "battery")
        assertEquals(HandshakeResult.Ready, result.handshake)
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.snapshot().batterySource)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertEquals(20, adapter.runtimeState().battery.case.percent)
        adapter.receive(mode(1, 1))
        assertEquals(NoiseMode.WIND, adapter.runtimeState().noiseMode)
        assertTrue(adapter.snapshot().capabilities.windNoiseControl)
        assertEquals(SwarmooMo3ProAdapter.PRESENTATION_ID, adapter.snapshot().presentationId)
    }

    @Test
    fun completeUnknownBatteryReportClearsOldValuesIncludingBox() {
        val adapter = SwarmooMo3ProAdapter()
        adapter.receive(battery(100, 99, 20), "battery")
        adapter.receive(battery(255, 255, 0), "battery")
        assertNull(adapter.runtimeState().battery.left.percent)
        assertNull(adapter.runtimeState().battery.right.percent)
        assertNull(adapter.runtimeState().battery.case.percent)
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.snapshot().batterySource)
    }

    @Test
    fun exceptionIsLimitedToCapturedStatusRoutesAndCommandsWithoutOptimisticState() {
        val adapter = SwarmooMo3ProAdapter()
        assertTrue(adapter.permitsUnconfirmedGattWrite(SwarmooMo3WireCodec.query, "battery", 1))
        assertFalse(adapter.permitsUnconfirmedGattWrite(SwarmooMo3WireCodec.query, "battery", 133))
        assertFalse(adapter.permitsUnconfirmedGattWrite(SwarmooMo3WireCodec.query, "other", 1))
        assertFalse(adapter.permitsUnconfirmedGattWrite(SwarmooMo3WireCodec.setMain(1), "battery", 1))
        assertFalse(StandardEarbudAdapter().permitsUnconfirmedGattWrite(SwarmooMo3WireCodec.query, null, 1))
        adapter.receive(mode(0, 0))
        val request = StandardControlRequest.SetNoiseMode(NoiseMode.WIND)
        assertEquals(ControlConfirmationPolicy.DEVICE_REPORT, adapter.controlPolicy(request).confirmation)
        val control = adapter.executeControl(request)
        assertTrue(control.accepted)
        assertEquals(NoiseMode.OFF, adapter.runtimeState().noiseMode)
        assertEquals(listOf(SwarmooMo3WireCodec.setMain(1), SwarmooMo3WireCodec.setSub(1)).map(ByteArray::toList),
            control.commands.map(ByteArray::toList))
        adapter.controlWritten(request)
        assertEquals(NoiseMode.OFF, adapter.runtimeState().noiseMode)
        assertFalse(adapter.receive(mode(1, 2)).stateChanged)
        assertEquals(NoiseMode.OFF, adapter.runtimeState().noiseMode)
        adapter.receive(mode(1, 1))
        assertEquals(NoiseMode.WIND, adapter.runtimeState().noiseMode)
    }

    @Test
    fun delayedReportsAreBoundedAndResetRevokesPrivateEvidence() {
        val adapter = SwarmooMo3ProAdapter()
        adapter.receive(mode(0, 0))
        adapter.controlWritten(StandardControlRequest.SetNoiseMode(NoiseMode.WIND))
        listOf(250L, 500L, 700L).forEach { delay ->
            val held = adapter.receive(mode(1, 2))
            assertEquals(listOf(AdapterEffect.RequestState(NoiseModeFeatureState.FEATURE_ID, delay)), held.effects)
            assertEquals(NoiseMode.OFF, adapter.runtimeState().noiseMode)
        }
        adapter.receive(mode(1, 2))
        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)
        adapter.resetProtocolSession()
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        adapter.receive(mode(2, 2))
        assertEquals(NoiseMode.TRANSPARENCY, adapter.runtimeState().noiseMode)
    }

    @Test
    fun controllerAndDisabledFallbackFollowSharedPolicy() {
        val adapter = SwarmooMo3ProAdapter()
        assertEquals(listOf(ControlAppCatalog.tigerListening), adapter.controlApps)
        assertEquals(ControlAppCatalog.tigerListening,
            ControlAppCatalog.activeOwner(adapter.controlApps, setOf("com.example.tigerlistening")))
        adapter.configureDisabledAdapterIds(setOf("swarmoo-family"))
        val fallback = adapter.resolveInitialProtocolFailure() as InitialProtocolFailureResolution.FallbackTo
        assertEquals(StandardEarbudAdapter.ID, fallback.adapter.id)
    }

    private fun battery(left: Int, right: Int, case: Int): ByteArray =
        byteArrayOf(0x55, 0xAA.toByte(), 0x0B, 0, left.toByte(), right.toByte(), case.toByte())

    private fun mode(main: Int, sub: Int): ByteArray =
        "55 AA 0B 00 00 01 02 00 00 00 00 00 01 FF 09 01 00 FF FF FF 01 00 00"
            .split(' ').map { it.toInt(16).toByte() }.toByteArray().apply {
                this[5] = main.toByte()
                this[6] = sub.toByte()
            }
}
