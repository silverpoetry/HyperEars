package dev.hyperears.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlRequestTransportTest {
    @Test
    fun standardRequestsRoundTripWithStableDiscriminators() {
        val requests = listOf(
            StandardControlRequest.Refresh,
            StandardControlRequest.SetNoiseMode(NoiseMode.ANC),
            StandardControlRequest.SetNoiseMode(NoiseMode.OFF),
            StandardControlRequest.SetNoiseMode(NoiseMode.TRANSPARENCY),
            StandardControlRequest.SetNoiseMode(NoiseMode.WIND),
        )

        requests.forEach { request ->
            assertEquals(request, ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
        }

        val encoded = ControlRequestTransport.encode(
            StandardControlRequest.SetNoiseMode(NoiseMode.ANC),
        )
        assertTrue(encoded.contains("\"command\":\"standard.set_noise_mode\""))
        assertTrue(encoded.contains("\"mode\":\"anc\""))
    }

    @Test
    fun edifierGameModeRequestRoundTripsWithoutExtendingTheStandardRequestFamily() {
        val request = EdifierControlRequest.SetGameMode(enabled = true)
        val encoded = ControlRequestTransport.encode(request)

        assertTrue(encoded.contains("\"command\":\"edifier.set_game_mode\""))
        assertFalse(encoded.contains("standard.set_game_mode"))
        assertEquals(request, ControlRequestTransport.decode(encoded))
    }

    @Test
    fun malformedUnknownAndOversizedEnvelopesAreRejected() {
        assertNull(
            ControlRequestTransport.decode(
                "{\"schemaVersion\":99,\"request\":{\"command\":\"standard.refresh\"}}",
            ),
        )
        assertNull(
            ControlRequestTransport.decode(
                "{\"schemaVersion\":1,\"request\":{\"command\":\"future.unknown\"}}",
            ),
        )
        assertNull(
            ControlRequestTransport.decode(
                "{\"schemaVersion\":1,\"request\":{\"command\":\"standard.refresh\",\"extra\":true}}",
            ),
        )
        assertNull(ControlRequestTransport.decode("not-json"))
        assertNull(ControlRequestTransport.decode("x".repeat(4 * 1024 + 1)))
    }

    @Test
    fun standardContractUsesEffectiveAdapterCapabilities() {
        val standard = StandardEarbudAdapter()
        val vivo = VivoTwsAir3ProAdapter()

        assertTrue(standard.supportsControl(StandardControlRequest.Refresh))
        assertFalse(
            standard.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)),
        )
        assertFalse(vivo.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))
        assertFalse(vivo.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.WIND)))
    }

    @Test
    fun modelControlPolicyUsesTheCommonConfirmationAndPacingContract() {
        val request = StandardControlRequest.SetNoiseMode(NoiseMode.ANC)
        val w860Policy = EdifierW860NBProAdapter().controlPolicy(request)
        val evoPolicy = EdifierEvoProAdapter().controlPolicy(request)

        assertEquals(ControlConfirmationPolicy.PUBLISH_AFTER_WRITE, w860Policy.confirmation)
        assertEquals(1_800L, w860Policy.cooldownMs)
        assertEquals(NoiseModeFeatureState(NoiseMode.ANC), w860Policy.stateAfterWrite)
        assertEquals(ControlConfirmationPolicy.PUBLISH_AFTER_WRITE, evoPolicy.confirmation)
        assertEquals(0L, evoPolicy.cooldownMs)
        assertEquals(NoiseModeFeatureState(NoiseMode.ANC), evoPolicy.stateAfterWrite)
    }

    @Test
    fun confirmationPolicySelectsOptimisticStateAndReadbackIndependently() {
        val request = StandardControlRequest.SetNoiseMode(NoiseMode.ANC)
        val optimisticState = NoiseModeFeatureState(NoiseMode.ANC)

        val deviceReport = TestControlAdapter(
            ControlExecutionPolicy(
                confirmation = ControlConfirmationPolicy.DEVICE_REPORT,
                stateAfterWrite = null,
            ),
        ).executeControl(request)
        assertFalse(deviceReport.stateChanged)
        assertEquals(listOf(READBACK.toList()), deviceReport.readback.map(ByteArray::toList))

        val afterWrite = TestControlAdapter(
            ControlExecutionPolicy(
                confirmation = ControlConfirmationPolicy.PUBLISH_AFTER_WRITE,
                stateAfterWrite = optimisticState,
            ),
        ).executeControl(request)
        assertTrue(afterWrite.stateChanged)
        assertTrue(afterWrite.readback.isEmpty())

        val afterWriteThenRefresh = TestControlAdapter(
            ControlExecutionPolicy(
                confirmation = ControlConfirmationPolicy.PUBLISH_AFTER_WRITE_THEN_REFRESH,
                stateAfterWrite = optimisticState,
            ),
        ).executeControl(request)
        assertTrue(afterWriteThenRefresh.stateChanged)
        assertEquals(
            listOf(READBACK.toList()),
            afterWriteThenRefresh.readback.map(ByteArray::toList),
        )
    }

    private class TestControlAdapter(
        private val policy: ControlExecutionPolicy,
    ) : EarbudAdapter() {
        override val id: String = "test-control-adapter"
        override val displayName: String = "Test control adapter"
        override val privateProtocolRequired: Boolean = true
        override val supportedNoiseModes: Set<NoiseMode> = setOf(NoiseMode.ANC)
        override val capabilities: EarbudCapabilities = EarbudCapabilities(noiseControl = true)

        override fun matches(identity: EarbudIdentity): Boolean = false

        override fun controlPolicy(request: ControlRequest): ControlExecutionPolicy = policy

        override fun createProtocolSession(): ProtocolSession = object : ProtocolSession {
            override fun initialReadCommands(): List<ByteArray> = emptyList()

            override fun encode(request: ControlRequest): List<ByteArray> = listOf(WRITE)

            override fun readback(request: ControlRequest): List<ByteArray> = listOf(READBACK)

            override fun offer(bytes: ByteArray): List<ProtocolEvent> = emptyList()

            override fun reset() = Unit
        }
    }

    private companion object {
        val WRITE = byteArrayOf(0x01)
        val READBACK = byteArrayOf(0x02)
    }
}
