package dev.hyperears.integration

import org.junit.Assert.*
import org.junit.Test

class FeatureReportNormalizationTest {
    private fun battery(value: Int) = BatteryFeatureState(EarbudBattery(left = BatteryReading(value, false)))

    private fun adapter(
        input: DeviceFeatureState,
        normalize: (DeviceFeatureState) -> DeviceFeatureState,
    ) = object : EarbudAdapter() {
        override val id = "normalization-test"
        override val displayName = id
        override fun matches(identity: EarbudIdentity) = false
        override val featureStateContract = DeviceFeatureStateContract { _, state ->
            state is BatteryFeatureState && state.battery.left.percent != 99
        }
        override fun normalizeFeatureReport(state: DeviceFeatureState) = normalize(state)
        override fun createProtocolSession() = object : ProtocolSession {
            override fun initialReadCommands() = emptyList<ByteArray>()
            override fun encode(request: ControlRequest) = emptyList<ByteArray>()
            override fun offer(bytes: ByteArray) = listOf(ProtocolEvent.FeatureStateChanged(input))
            override fun reset() = Unit
        }
    }

    @Test fun normalizationRetainsFeatureAndRechecksDeclaredContract() {
        val valid = adapter(battery(40)) { battery(41) }
        assertTrue(valid.receive(byteArrayOf()).stateChanged)
        assertEquals(41, valid.runtimeState().battery.left.percent)
        for (replacement in listOf(battery(99), NoiseModeFeatureState(NoiseMode.ANC))) {
            val rejected = adapter(battery(40)) { replacement }
            assertFalse(rejected.receive(byteArrayOf()).stateChanged)
            assertNull(rejected.runtimeState().battery.left.percent)
            assertNull(rejected.runtimeState().features.get<NoiseModeFeatureState>())
        }
    }

    @Test fun undeclaredInputNeverInvokesNormalization() {
        var invoked = false
        val adapter = adapter(NoiseModeFeatureState(NoiseMode.ANC)) { invoked = true; battery(41) }
        adapter.receive(byteArrayOf())
        assertFalse(invoked)
        assertNull(adapter.runtimeState().battery.left.percent)
    }
}
