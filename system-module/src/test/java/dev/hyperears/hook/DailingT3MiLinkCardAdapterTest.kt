package dev.hyperears.hook

import dev.hyperears.integration.AdapterRuntimeState
import dev.hyperears.integration.ControlAppSpec
import dev.hyperears.integration.ControlOwnership
import dev.hyperears.integration.DailingAncDepth
import dev.hyperears.integration.DailingAncDepthFeatureState
import dev.hyperears.integration.DailingMiLinkPresentationIds
import dev.hyperears.integration.DeviceLifecycle
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.NoiseMode
import dev.hyperears.integration.NoiseModeFeatureState
import dev.hyperears.integration.PrivateTransportState
import dev.hyperears.integration.ProtocolHandshakeState
import dev.hyperears.integration.SystemProfileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DailingT3MiLinkCardAdapterTest {
    @Test
    fun registryResolvesTheDailingDepthPresentation() {
        assertSame(
            DailingT3CardAdapter,
            MiLinkCardAdapterRegistry.resolve(DailingMiLinkPresentationIds.CARD),
        )
        // The native three-state row stays untouched, so no other stock surface is borrowed.
        assertEquals(MiLinkNativeCardSurface.NONE, DailingT3CardAdapter.nativeSurface)
        assertNull(
            DailingT3CardAdapter.nativeSurfaceNoiseMode(
                connectedState(depth = DailingAncDepth.DEEP),
            ),
        )
    }

    @Test
    fun confirmedNoiseCapabilityShowsTheRowOnlyWhileAncIsActive() {
        val anc = connectedState(depth = DailingAncDepth.DEEP, noiseMode = NoiseMode.ANC)
        val transparency = connectedState(
            depth = DailingAncDepth.DEEP,
            noiseMode = NoiseMode.TRANSPARENCY,
        )

        assertEquals(
            DailingAncDepthRowPolicy.RowState(
                visible = true,
                enabled = true,
                current = DailingAncDepth.DEEP,
            ),
            DailingAncDepthRowPolicy.render(anc),
        )
        assertFalse(DailingAncDepthRowPolicy.render(transparency).visible)
    }

    @Test
    fun depthStartsUnknownUntilTheModuleWritesALevel() {
        val state = connectedState(depth = null, noiseMode = NoiseMode.ANC)

        assertTrue(DailingAncDepthRowPolicy.render(state).visible)
        assertNull(DailingAncDepthRowPolicy.render(state).current)
        assertEquals(
            DailingAncDepth.LIGHT,
            DailingAncDepthRowPolicy.request(state, DailingAncDepth.LIGHT),
        )
    }

    @Test
    fun onlyAConnectedSessionMaySendADepthTransition() {
        val connected = connectedState(depth = DailingAncDepth.DEEP, noiseMode = NoiseMode.ANC)
        val disconnected = connected.copy(
            lifecycle = connected.lifecycle.copy(systemProfile = SystemProfileState.DISCONNECTED),
        )

        assertEquals(
            DailingAncDepth.LIGHT,
            DailingAncDepthRowPolicy.request(connected, DailingAncDepth.LIGHT),
        )
        assertNull(
            DailingAncDepthRowPolicy.request(connected, DailingAncDepth.DEEP),
        )
        assertNull(
            DailingAncDepthRowPolicy.request(disconnected, DailingAncDepth.LIGHT),
        )
    }

    @Test
    fun staleFeatureDuringYieldOrRecoveryCannotEnableTheRow() {
        val connected = connectedState(depth = DailingAncDepth.DEEP, noiseMode = NoiseMode.ANC)
        val states = listOf(
            connected.copy(
                lifecycle = connected.lifecycle.copy(
                    controlOwnership = ControlOwnership.EXTERNAL_APP,
                    externalControlApp = ControlAppSpec("test.controller", "Test controller"),
                    privateTransport = PrivateTransportState.NOT_REQUIRED,
                ),
            ),
            connected.copy(
                lifecycle = connected.lifecycle.copy(
                    privateTransport = PrivateTransportState.IDLE,
                ),
            ),
            connected.copy(
                lifecycle = connected.lifecycle.copy(
                    protocolHandshake = ProtocolHandshakeState.PENDING,
                ),
            ),
        )
        states.forEach { state ->
            assertFalse(DailingAncDepthRowPolicy.render(state).enabled)
            assertNull(
                DailingAncDepthRowPolicy.request(state, DailingAncDepth.LIGHT),
            )
        }
    }

    private fun connectedState(
        depth: DailingAncDepth?,
        noiseMode: NoiseMode = NoiseMode.ANC,
        publishDepthFeature: Boolean = true,
    ): EarbudState {
        val runtime = AdapterRuntimeState().let { state ->
            if (!publishDepthFeature) {
                state
            } else {
                state.copy(
                    features = state.features.update(
                        DailingAncDepthFeatureState(
                            current = depth,
                            supported = DailingAncDepthFeatureState.ALL_DEPTHS,
                        ),
                    ),
                )
            }
        }
        return EarbudState(
            lifecycle = DeviceLifecycle(
                systemProfile = SystemProfileState.CONNECTED,
                privateTransport = PrivateTransportState.CONNECTED,
                protocolHandshake = ProtocolHandshakeState.CONFIRMED,
            ),
            features = runtime.features.update(NoiseModeFeatureState(noiseMode)),
        )
    }
}
