package dev.hyperears.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureStateTransportTest {
    @Test
    fun typedStandardStatesRoundTripInOneVersionedSnapshot() {
        val snapshot = DeviceFeatureSnapshot()
            .update(
                BatteryFeatureState(
                    EarbudBattery(
                        left = BatteryReading(percent = 81, charging = false),
                        right = BatteryReading(percent = 79, charging = true),
                        case = BatteryReading(percent = 62, charging = false),
                    ),
                ),
            )
            .update(NoiseModeFeatureState(NoiseMode.WIND))

        val encoded = FeatureStateTransport.encode(snapshot)

        assertTrue(encoded.contains("\"schemaVersion\":1"))
        assertTrue(encoded.contains("\"feature\":\"standard.battery\""))
        assertTrue(encoded.contains("\"feature\":\"standard.noise_mode\""))
        assertEquals(snapshot, FeatureStateTransport.decode(encoded))
    }

    @Test
    fun edifierGameModeStateRoundTripsAsAnAdapterOwnedFeature() {
        val snapshot = DeviceFeatureSnapshot()
            .update(EdifierGameModeFeatureState(enabled = true))
        val encoded = FeatureStateTransport.encode(snapshot)

        assertTrue(encoded.contains("\"feature\":\"edifier.game_mode\""))
        assertEquals(snapshot, FeatureStateTransport.decode(encoded))
    }

    @Test
    fun sameFeatureIdentityReplacesThePreviousValue() {
        val snapshot = DeviceFeatureSnapshot()
            .update(NoiseModeFeatureState(NoiseMode.ANC))
            .update(NoiseModeFeatureState(NoiseMode.OFF))

        assertEquals(1, snapshot.values.size)
        assertEquals(NoiseMode.OFF, snapshot.get<NoiseModeFeatureState>()?.mode)
    }

    @Test
    fun malformedUnknownAndOversizedSnapshotsAreRejected() {
        assertNull(FeatureStateTransport.decode("not-json"))
        assertNull(
            FeatureStateTransport.decode(
                "{\"schemaVersion\":2,\"snapshot\":{\"values\":[]}}",
            ),
        )
        assertNull(
            FeatureStateTransport.decode(
                "{\"schemaVersion\":1,\"snapshot\":{\"values\":[{\"feature\":\"future.state\"}]}}",
            ),
        )
        assertNull(FeatureStateTransport.decode("x".repeat(16 * 1024 + 1)))
    }

    @Test
    fun removingNoiseModeOnlyRemovesItsFeatureState() {
        val state = EarbudState()
            .withFeature(BatteryFeatureState(EarbudBattery.fromAggregate(66)))
            .withNoiseMode(NoiseMode.ANC)
            .withNoiseMode(null)

        assertEquals(66, state.battery.overall.percent)
        assertNull(state.noiseMode)
        assertEquals(1, state.features.values.size)
    }

    @Test
    fun adapterStoresIncomingFeatureEventsInTheUnifiedSnapshot() {
        val adapter = TestFeatureAdapter(
            listOf(
                ProtocolEvent.FeatureStateChanged(
                    BatteryFeatureState(EarbudBattery.fromAggregate(55)),
                ),
                ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(NoiseMode.TRANSPARENCY)),
            ),
        )

        val result = adapter.receive(byteArrayOf(0x01))

        assertTrue(result.stateChanged)
        assertEquals(55, adapter.runtimeState().features
            .get<BatteryFeatureState>()
            ?.battery
            ?.overall
            ?.percent)
        assertEquals(
            NoiseMode.TRANSPARENCY,
            adapter.runtimeState().features.get<NoiseModeFeatureState>()?.mode,
        )
    }

    @Test
    fun replacementAdapterRetainsOnlyItsDeclaredFeatureTypes() {
        val source = AdapterRuntimeState(
            features = DeviceFeatureSnapshot()
                .update(BatteryFeatureState(EarbudBattery.fromAggregate(42)))
                .update(NoiseModeFeatureState(NoiseMode.ANC)),
        )
        val adapter = BatteryOnlyFeatureAdapter()

        assertTrue(adapter.adoptRuntimeState(source))
        assertEquals(42, adapter.runtimeState().battery.overall.percent)
        assertNull(adapter.runtimeState().noiseMode)
        assertFalse(adapter.adoptRuntimeState(adapter.runtimeState()))
    }

    private open class TestFeatureAdapter(
        private val events: List<ProtocolEvent> = emptyList(),
    ) : EarbudAdapter() {
        override val id: String = "test-feature-adapter"
        override val displayName: String = "Test feature adapter"

        override fun matches(identity: EarbudIdentity): Boolean = false

        override fun createProtocolSession(): ProtocolSession = object : ProtocolSession {
            override fun initialReadCommands(): List<ByteArray> = emptyList()

            override fun encode(request: ControlRequest): List<ByteArray> = emptyList()

            override fun offer(bytes: ByteArray): List<ProtocolEvent> = events

            override fun reset() = Unit
        }
    }

    private class BatteryOnlyFeatureAdapter : TestFeatureAdapter() {
        override val featureStateContract: DeviceFeatureStateContract =
            DeviceFeatureStateContract { _, state -> state is BatteryFeatureState }
    }
}
