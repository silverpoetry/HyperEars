package dev.hyperears.integration

import dev.hyperears.protocol.moondrop.MoondropPuddingWireCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoondropMirageAdapterTest {
    @Test
    fun exactNameSelectsMirageWithoutCachedSppUuid() {
        val adapter = requireNotNull(
            EarbudAdapterRegistry.resolve(
                EarbudIdentity(
                    deviceName = "MOONDROP MIRAGE",
                    standardHeadset = true,
                ),
            ),
        )
        assertTrue(adapter is MoondropMirageAdapter)
        assertEquals(MoondropMirageAdapter.ID, adapter.id)
    }

    @Test
    fun coBrandNameSelectsMirage() {
        val adapter = requireNotNull(
            EarbudAdapterRegistry.resolve(
                EarbudIdentity(
                    deviceName = "HATSUNE MIKU × MOONDROP",
                    standardHeadset = true,
                ),
            ),
        )
        assertTrue(adapter is MoondropMirageAdapter)
    }

    @Test
    fun chineseBrandMirageNameSelectsMirage() {
        val adapter = requireNotNull(
            EarbudAdapterRegistry.resolve(
                EarbudIdentity(
                    deviceName = "水月雨 MIRAGE",
                    standardHeadset = true,
                ),
            ),
        )
        assertTrue(adapter is MoondropMirageAdapter)
    }

    @Test
    fun standardSppUuidDoesNotSelectMoondrop() {
        val adapter = requireNotNull(
            EarbudAdapterRegistry.resolve(
                EarbudIdentity(
                    deviceName = "Unrelated headset",
                    standardHeadset = true,
                    serviceUuids = setOf(MoondropMirageAdapter.STANDARD_SPP_UUID),
                ),
            ),
        )
        assertTrue(adapter is StandardEarbudAdapter)
        assertFalse(adapter is MoondropEarbudAdapter)
    }

    @Test
    fun bareMirageKeywordWithoutBrandDoesNotSelectMoondrop() {
        val adapter = requireNotNull(
            EarbudAdapterRegistry.resolve(
                EarbudIdentity(
                    deviceName = "Desired Mirage 500",
                    standardHeadset = true,
                ),
            ),
        )
        assertTrue(adapter is StandardEarbudAdapter)
        assertFalse(adapter is MoondropEarbudAdapter)
    }

    @Test
    fun bareMikuKeywordWithoutBrandDoesNotSelectMoondrop() {
        val adapter = requireNotNull(
            EarbudAdapterRegistry.resolve(
                EarbudIdentity(
                    deviceName = "MIKU Speaker",
                    standardHeadset = true,
                ),
            ),
        )
        assertTrue(adapter is StandardEarbudAdapter)
        assertFalse(adapter is MoondropEarbudAdapter)
    }

    @Test
    fun unrelatedMoondropNameFallsBackToFamilyAdapter() {
        val adapter = requireNotNull(
            EarbudAdapterRegistry.resolve(
                EarbudIdentity(
                    deviceName = "MOONDROP Xyz",
                    standardHeadset = true,
                ),
            ),
        )
        assertTrue(adapter is MoondropEarbudAdapter)
        assertFalse(adapter is MoondropMirageAdapter)
        assertEquals(MoondropEarbudAdapter.ID, adapter.id)
    }

    @Test
    fun privateCapabilitiesRemainClosedUntilHandshakeAndReadResponse() {
        val adapter = MoondropMirageAdapter()
        assertTrue(
            adapter.beginHandshake().commands.single()
                .contentEquals(MoondropPuddingWireCodec.handshake),
        )
        assertTrue(adapter.snapshot().capabilities.battery)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertTrue(adapter.snapshot().supportedNoiseModes.isEmpty())
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)

        val handshake = MoondropPuddingWireCodec.frame(
            command = 0x0A,
            subcommand = 0x83,
            opcode = 0x00,
            parameters = byteArrayOf(0, 4, 3, 1),
        )
        val accepted = adapter.receive(handshake)
        assertEquals(HandshakeResult.Ready, accepted.handshake)
        assertEquals(
            listOf(
                MoondropPuddingWireCodec.queryBattery.toList(),
                MoondropPuddingWireCodec.queryNoiseMode.toList(),
            ),
            accepted.commands.map(ByteArray::toList),
        )
        assertFalse(adapter.snapshot().capabilities.noiseControl)

        adapter.receive(
            MoondropPuddingWireCodec.frame(
                command = 0x1D,
                subcommand = 0x41,
                opcode = 0x03,
                parameters = byteArrayOf(1),
            ),
        )
        assertTrue(adapter.snapshot().capabilities.noiseControl)
        assertEquals(
            setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
            adapter.snapshot().supportedNoiseModes,
        )
        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)
    }

    @Test
    fun batteryQueriesAreSentAfterHandshakeAndOnRefresh() {
        val adapter = MoondropMirageAdapter()
        val accepted = adapter.receive(
            MoondropPuddingWireCodec.frame(
                command = 0x0A,
                subcommand = 0x83,
                opcode = 0x00,
                parameters = byteArrayOf(0, 4, 3, 1),
            ),
        )
        assertEquals(HandshakeResult.Ready, accepted.handshake)
        assertEquals(
            listOf(
                MoondropPuddingWireCodec.queryBattery.toList(),
                MoondropPuddingWireCodec.queryNoiseMode.toList(),
            ),
            accepted.commands.map(ByteArray::toList),
        )
        assertEquals(
            listOf(
                MoondropPuddingWireCodec.queryBattery.toList(),
                MoondropPuddingWireCodec.queryNoiseMode.toList(),
            ),
            adapter.executeControl(StandardControlRequest.Refresh)
                .commands.map(ByteArray::toList),
        )
        assertEquals(
            listOf(MoondropPuddingWireCodec.queryBattery.toList()),
            adapter.queryState(BatteryFeatureState.FEATURE_ID).map(ByteArray::toList),
        )
    }

    @Test
    fun batteryResponseCommitsPrivateSourceAndReportsPerBudValues() {
        val adapter = readyAdapter()

        adapter.receive(
            MoondropPuddingWireCodec.frame(
                command = 0x1D,
                subcommand = 0x1B,
                opcode = 0x01,
                parameters = byteArrayOf(1, 91, 2, 76, 3, 0xFF.toByte()),
            ),
        )

        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.snapshot().batterySource)
        assertEquals(91, adapter.runtimeState().battery.left.percent)
        assertEquals(76, adapter.runtimeState().battery.right.percent)
        assertEquals(null, adapter.runtimeState().battery.case.percent)
    }

    @Test
    fun noiseModeWritePublishesOptimisticallyThenRequestsDelayedReadback() {
        val adapter = readyAdapterWithNoiseMode(NoiseMode.OFF)

        val result = adapter.executeControl(
            StandardControlRequest.SetNoiseMode(NoiseMode.TRANSPARENCY),
        )
        val policy = adapter.controlPolicy(
            StandardControlRequest.SetNoiseMode(NoiseMode.TRANSPARENCY),
        )
        assertTrue(result.accepted)
        assertTrue(result.stateChanged)
        assertEquals(ControlConfirmationPolicy.PUBLISH_AFTER_WRITE, policy.confirmation)
        assertEquals(
            NoiseModeFeatureState(NoiseMode.TRANSPARENCY),
            policy.stateAfterWrite,
        )
        assertEquals(
            listOf(
                MoondropPuddingWireCodec
                    .setNoiseMode(MoondropPuddingWireCodec.NoiseMode.TRANSPARENCY)
                    .toList(),
            ),
            result.commands.map(ByteArray::toList),
        )
        assertTrue(result.readback.isEmpty())
        assertEquals(NoiseMode.TRANSPARENCY, adapter.runtimeState().noiseMode)
        assertEquals(
            listOf(
                AdapterEffect.CancelStateRequest(NoiseModeFeatureState.FEATURE_ID),
                AdapterEffect.RequestState(
                    NoiseModeFeatureState.FEATURE_ID,
                    MoondropMirageAdapter.INITIAL_MODE_QUERY_DELAY_MS,
                ),
            ),
            adapter.controlWritten(StandardControlRequest.SetNoiseMode(NoiseMode.TRANSPARENCY)),
        )
        assertEquals(
            InitialProtocolFailureResolution.KeepDormant,
            adapter.onInitialProtocolUnavailable(),
        )
    }

    @Test
    fun staleModeReadbacksKeepTheOptimisticStateAndScheduleBoundedReads() {
        val adapter = readyAdapterWithNoiseMode(NoiseMode.OFF)
        val control = adapter.executeControl(
            StandardControlRequest.SetNoiseMode(NoiseMode.ANC),
        )
        adapter.controlWritten(StandardControlRequest.SetNoiseMode(NoiseMode.ANC))
        assertTrue(control.accepted)
        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)

        MoondropMirageAdapter.MODE_CONFIRMATION_DELAYS_MS.forEach { delayMs ->
            val result = adapter.receive(noiseModeFrame(NoiseMode.OFF))

            assertFalse(result.stateChanged)
            assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)
            assertEquals(1, result.effects.size)
            val followUp = result.effects.single() as AdapterEffect.RequestState
            assertEquals(NoiseModeFeatureState.FEATURE_ID, followUp.featureId)
            assertEquals(delayMs, followUp.delayMs)
            assertEquals(
                listOf(MoondropPuddingWireCodec.queryNoiseMode.toList()),
                adapter.queryState(followUp.featureId).map(ByteArray::toList),
            )
        }

        val finalResult = adapter.receive(noiseModeFrame(NoiseMode.OFF))

        assertTrue(finalResult.stateChanged)
        assertEquals(NoiseMode.OFF, adapter.runtimeState().noiseMode)
        assertEquals(
            listOf(AdapterEffect.CancelStateRequest(NoiseModeFeatureState.FEATURE_ID)),
            finalResult.effects,
        )
    }

    private fun readyAdapter(): MoondropMirageAdapter = MoondropMirageAdapter().also(
        ::acceptHandshake,
    )

    private fun readyAdapterWithNoiseMode(initialMode: NoiseMode): MoondropMirageAdapter =
        MoondropMirageAdapter().also { adapter ->
            acceptHandshake(adapter)
            adapter.receive(noiseModeFrame(initialMode))
            assertEquals(initialMode, adapter.runtimeState().noiseMode)
        }

    private fun acceptHandshake(adapter: MoondropMirageAdapter) {
        val result = adapter.receive(
            MoondropPuddingWireCodec.frame(
                command = 0x0A,
                subcommand = 0x83,
                opcode = 0x00,
                parameters = byteArrayOf(0, 4, 3, 1),
            ),
        )
        assertEquals(HandshakeResult.Ready, result.handshake)
    }

    private fun noiseModeFrame(mode: NoiseMode): ByteArray =
        MoondropPuddingWireCodec.frame(
            command = 0x1D,
            subcommand = 0x41,
            opcode = 0x03,
            parameters = byteArrayOf(
                when (mode) {
                    NoiseMode.OFF -> 0
                    NoiseMode.ANC -> 1
                    NoiseMode.TRANSPARENCY -> 2
                    NoiseMode.WIND -> error("MIRAGE does not support wind mode")
                },
            ),
        )
}
