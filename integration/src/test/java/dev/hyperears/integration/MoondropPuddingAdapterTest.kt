package dev.hyperears.integration

import dev.hyperears.protocol.moondrop.MoondropPuddingWireCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoondropPuddingAdapterTest {
    private fun snapshot(left: Int, right: Int, case: Int) = byteArrayOf(
        -1, 4, 0, 6, 0, 0x1d, 0x1b, 1, 1, left.toByte(), 2, right.toByte(), 3, case.toByte(),
    )

    @Test fun committedSnapshotClearsIndividualUnavailableComponents() {
        val adapter = readyAdapter()
        adapter.receive(snapshot(80, 70, 60))
        adapter.receive(snapshot(0, 71, 255))
        assertNull(adapter.runtimeState().battery.left.percent)
        assertEquals(71, adapter.runtimeState().battery.right.percent)
        assertNull(adapter.runtimeState().battery.case.percent)
        adapter.receive(snapshot(81, 255, 0))
        assertEquals(81, adapter.runtimeState().battery.left.percent)
        assertNull(adapter.runtimeState().battery.right.percent)
        assertEquals(0, adapter.runtimeState().battery.case.percent)
    }

    @Test fun coalescedSnapshotAdmissionUsesEventOrder() {
        val adapter = readyAdapter()
        adapter.receive(snapshot(80, 70, 60) + snapshot(0, 71, 255))
        assertNull(adapter.runtimeState().battery.left.percent)
        assertEquals(71, adapter.runtimeState().battery.right.percent)
        assertNull(adapter.runtimeState().battery.case.percent)
    }

    @Test fun exhaustedBootstrapStopsMergingAndResetRestoresInitialPolicy() {
        val adapter = readyAdapter()
        val initial = snapshot(80, 255, 60)
        // All reports in one read also exercise the bounded policy's event ordering.
        adapter.receive((0..MoondropPuddingAdapter.BATTERY_BOOTSTRAP_DELAYS_MS.size)
            .fold(byteArrayOf()) { bytes, _ -> bytes + initial } + snapshot(0, 255, 61))
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.snapshot().batterySource)
        assertNull(adapter.runtimeState().battery.left.percent)
        assertEquals(61, adapter.runtimeState().battery.case.percent)
        adapter.resetProtocolSession()
        adapter.receive(byteArrayOf(-1, 4, 0, 4, 0, 10, -125, 0, 0, 4, 3, 1))
        adapter.receive(snapshot(0, 72, 62) + snapshot(82, 0, 255))
        assertEquals(82, adapter.runtimeState().battery.left.percent)
        assertEquals(72, adapter.runtimeState().battery.right.percent)
        assertEquals(62, adapter.runtimeState().battery.case.percent)
    }

    @Test fun malformedMixedSnapshotDoesNotClearOrUpdateAnyComponent() {
        val adapter = readyAdapter()
        adapter.receive(snapshot(80, 70, 60))
        val before = adapter.runtimeState().battery
        adapter.receive(snapshot(101, 71, 255))
        assertEquals(before, adapter.runtimeState().battery)
    }

    @Test fun explicitUnavailableSnapshotClearsPreviouslyConfirmedBattery() {
        val adapter = readyAdapter()
        fun report(left: Int, right: Int, case: Int) = MoondropPuddingWireCodec.frame(
            command = 0x1d, subcommand = 0x1b, opcode = 1,
            parameters = byteArrayOf(1, left.toByte(), 2, right.toByte(), 3, case.toByte()))
        adapter.receive(report(0, 255, 255))
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        adapter.receive(report(80, 70, 60))
        assertEquals(80, adapter.runtimeState().battery.left.percent)
        adapter.receive(report(0, 255, 255))
        assertEquals(EarbudBattery(), adapter.runtimeState().battery)
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.snapshot().batterySource)
        adapter.receive(report(85, 75, 65))
        adapter.receive(report(101, 120, 128))
        assertEquals(85, adapter.runtimeState().battery.left.percent)
    }
    @Test
    fun exactNameSelectsPuddingWithoutCachedSppUuid() {
        val adapter = requireNotNull(
            EarbudAdapterRegistry.resolve(
                EarbudIdentity(
                    deviceName = "MOONDROP Pudding",
                    standardHeadset = true,
                ),
            ),
        )
        assertTrue(adapter is MoondropPuddingAdapter)
        assertEquals(MoondropPuddingAdapter.ID, adapter.id)
    }

    @Test
    fun standardSppUuidDoesNotSelectMoondrop() {
        val adapter = requireNotNull(
            EarbudAdapterRegistry.resolve(
                EarbudIdentity(
                    deviceName = "Unrelated headset",
                    standardHeadset = true,
                    serviceUuids = setOf(MoondropPuddingAdapter.STANDARD_SPP_UUID),
                ),
            ),
        )
        assertTrue(adapter is StandardEarbudAdapter)
        assertFalse(adapter is MoondropEarbudAdapter)
    }

    @Test
    fun brandQualifiedPuddingKeywordIsStillANameOnlyCandidate() {
        val adapter = requireNotNull(
            EarbudAdapterRegistry.resolve(
                EarbudIdentity(
                    deviceName = "MOONDROP Pudding TWS",
                    standardHeadset = true,
                ),
            ),
        )
        assertTrue(adapter is MoondropPuddingAdapter)
    }

    @Test
    fun privateCapabilitiesRemainClosedUntilHandshakeAndReadResponse() {
        val adapter = MoondropPuddingAdapter()
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
        assertNull(adapter.runtimeState().battery.case.percent)

        val push = adapter.receive(
            MoondropPuddingWireCodec.frame(
                command = 0x1D,
                subcommand = 0x81,
                opcode = 0x01,
                parameters = byteArrayOf(1, 97, 2, 100, 3, 88),
            ),
        )
        assertTrue(push.stateChanged)
        assertEquals(97, adapter.runtimeState().battery.left.percent)
        assertEquals(100, adapter.runtimeState().battery.right.percent)
        assertEquals(88, adapter.runtimeState().battery.case.percent)
    }

    @Test
    fun splitBatteryReportsAreMergedBeforePrivateBatteryIsCommitted() {
        val adapter = readyAdapter()
        val first = adapter.receive(
            MoondropPuddingWireCodec.frame(
                command = 0x1D,
                subcommand = 0x1B,
                opcode = 0x01,
                parameters = byteArrayOf(1, 0, 2, 92, 3, 88),
            ),
        )

        assertFalse(first.stateChanged)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        assertEquals(
            AdapterEffect.RequestState(
                BatteryFeatureState.FEATURE_ID,
                MoondropPuddingAdapter.BATTERY_BOOTSTRAP_DELAYS_MS.first(),
            ),
            first.effects.single(),
        )

        val second = adapter.receive(
            MoondropPuddingWireCodec.frame(
                command = 0x1D,
                subcommand = 0x81,
                opcode = 0x01,
                parameters = byteArrayOf(1, 91, 2, 0, 3, 0xFF.toByte()),
            ),
        )

        assertTrue(second.stateChanged)
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.snapshot().batterySource)
        assertEquals(91, adapter.runtimeState().battery.left.percent)
        assertEquals(92, adapter.runtimeState().battery.right.percent)
        assertEquals(88, adapter.runtimeState().battery.case.percent)
        assertEquals(
            AdapterEffect.CancelStateRequest(BatteryFeatureState.FEATURE_ID),
            second.effects.single(),
        )
    }

    @Test
    fun batteryQueriesAreSentAfterHandshakeAndOnRefresh() {
        val adapter = MoondropPuddingAdapter()
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
    fun partialBatteryDoesNotInventANoiseControlGate() {
        val adapter = readyAdapterWithNoiseMode(NoiseMode.ANC)
        adapter.receive(
            MoondropPuddingWireCodec.frame(
                command = 0x1D,
                subcommand = 0x1B,
                opcode = 0x01,
                parameters = byteArrayOf(1, 0, 2, 92, 3, 88),
            ),
        )
        assertTrue(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))
        val result = adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC))
        assertTrue(result.accepted)
        assertTrue(result.commands.isNotEmpty())
    }

    @Test
    fun unknownBatteryNeverBlocksNoiseModeSwitches() {
        val adapter = readyAdapterWithNoiseMode(NoiseMode.ANC)
        assertTrue(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))

        assertTrue(adapter.onSystemBatteryChanged(73))
        assertTrue(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))
        assertTrue(
            adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)).accepted,
        )
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
                    MoondropPuddingAdapter.INITIAL_MODE_QUERY_DELAY_MS,
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

        MoondropPuddingAdapter.MODE_CONFIRMATION_DELAYS_MS.forEach { delayMs ->
            val result = adapter.receive(noiseModeFrame(NoiseMode.OFF))

            assertFalse(result.stateChanged)
            assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)
            val followUp = result.requireStateRequest()
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

    @Test
    fun expectedModeReadbackCompletesConfirmationWithoutRepublishingTheSameState() {
        val adapter = readyAdapterWithNoiseMode(NoiseMode.OFF)
        adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.TRANSPARENCY))
        adapter.controlWritten(StandardControlRequest.SetNoiseMode(NoiseMode.TRANSPARENCY))
        adapter.receive(noiseModeFrame(NoiseMode.OFF))

        val result = adapter.receive(noiseModeFrame(NoiseMode.TRANSPARENCY))

        assertFalse(result.stateChanged)
        assertEquals(NoiseMode.TRANSPARENCY, adapter.runtimeState().noiseMode)
        assertEquals(
            listOf(AdapterEffect.CancelStateRequest(NoiseModeFeatureState.FEATURE_ID)),
            result.effects,
        )

        val unsolicited = adapter.receive(noiseModeFrame(NoiseMode.OFF))
        assertTrue(unsolicited.stateChanged)
        assertEquals(NoiseMode.OFF, adapter.runtimeState().noiseMode)
        assertTrue(unsolicited.effects.isEmpty())
    }

    @Test
    fun confirmEchoArrivingInTheSameReadCancelsAnEarlierDeferredQuery() {
        val adapter = readyAdapterWithNoiseMode(NoiseMode.OFF)
        adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC))
        adapter.controlWritten(StandardControlRequest.SetNoiseMode(NoiseMode.ANC))

        val result = adapter.receive(
            noiseModeFrame(NoiseMode.OFF) + noiseModeFrame(NoiseMode.ANC),
        )

        assertFalse(result.stateChanged)
        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)
        assertEquals(
            listOf(
                AdapterEffect.RequestState(
                    NoiseModeFeatureState.FEATURE_ID,
                    MoondropPuddingAdapter.MODE_CONFIRMATION_DELAYS_MS.first(),
                ),
                AdapterEffect.CancelStateRequest(NoiseModeFeatureState.FEATURE_ID),
            ),
            result.effects,
        )
    }

    @Test
    fun protocolResetClearsModelOwnedConfirmationState() {
        val adapter = readyAdapterWithNoiseMode(NoiseMode.OFF)
        adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC))
        adapter.controlWritten(StandardControlRequest.SetNoiseMode(NoiseMode.ANC))

        adapter.resetProtocolSession()
        acceptHandshake(adapter)
        val result = adapter.receive(noiseModeFrame(NoiseMode.OFF))

        assertTrue(result.stateChanged)
        assertEquals(NoiseMode.OFF, adapter.runtimeState().noiseMode)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun nonModeControlsKeepTheDefaultReadbackDelay() {
        val policy = MoondropPuddingAdapter().controlPolicy(StandardControlRequest.Refresh)

        assertEquals(
            ControlExecutionPolicy.DEFAULT_READBACK_DELAY_MS,
            policy.readbackDelayMs,
        )
    }

    private fun readyAdapter(): MoondropPuddingAdapter =
        MoondropPuddingAdapter().also(::acceptHandshake)

    private fun readyAdapterWithNoiseMode(initialMode: NoiseMode): MoondropPuddingAdapter =
        MoondropPuddingAdapter().also { adapter ->
            acceptHandshake(adapter)
            adapter.receive(noiseModeFrame(initialMode))
            assertEquals(initialMode, adapter.runtimeState().noiseMode)
        }

    private fun acceptHandshake(adapter: MoondropPuddingAdapter) {
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
                    NoiseMode.WIND -> error("Pudding does not support wind mode")
                },
            ),
        )

    private fun AdapterIoResult.requireStateRequest(): AdapterEffect.RequestState {
        assertEquals(1, effects.size)
        return effects.single() as AdapterEffect.RequestState
    }
}
