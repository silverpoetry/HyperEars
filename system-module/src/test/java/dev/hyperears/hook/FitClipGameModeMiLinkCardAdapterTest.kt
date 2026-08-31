package dev.hyperears.hook

import dev.hyperears.integration.AdapterRuntimeState
import dev.hyperears.integration.DeviceLifecycle
import dev.hyperears.integration.EdifierGameModeFeatureState
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.NoiseMode
import dev.hyperears.integration.PrivateTransportState
import dev.hyperears.integration.ProtocolHandshakeState
import dev.hyperears.integration.SystemProfileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FitClipGameModeMiLinkCardAdapterTest {
    @Test
    fun gameModePresentationUsesTheStockThreeStateSurface() {
        assertEquals(
            MiLinkNativeCardSurface.ANC_THREE_STATE,
            FitClipUltraGameModeMiLinkCardAdapter.nativeSurface,
        )
        assertEquals(
            NoiseMode.OFF,
            FitClipUltraGameModeMiLinkCardAdapter.nativeSurfaceNoiseMode(
                connectedState(gameMode = false),
            ),
        )
        assertEquals(
            NoiseMode.ANC,
            FitClipUltraGameModeMiLinkCardAdapter.nativeSurfaceNoiseMode(
                connectedState(gameMode = true),
            ),
        )
    }

    @Test
    fun confirmedFeatureEnablesTheToggleAndProjectsTheReportedValue() {
        val enabled = connectedState(gameMode = true)
        val disabled = connectedState(gameMode = false)

        assertEquals(
            FitClipGameModeCardPolicy.CardState(gameMode = true, enabled = true),
            FitClipGameModeCardPolicy.render(enabled),
        )
        assertEquals(
            FitClipGameModeCardPolicy.CardState(gameMode = false, enabled = true),
            FitClipGameModeCardPolicy.render(disabled),
        )
    }

    @Test
    fun missingProtocolEvidenceNeverCreatesAnActionableControl() {
        val state = connectedState(gameMode = null)

        assertFalse(FitClipGameModeCardPolicy.render(state).enabled)
        assertNull(FitClipGameModeCardPolicy.request(state, gameMode = true))
    }

    @Test
    fun onlyAConnectedSessionMaySendARealStateTransition() {
        val off = connectedState(gameMode = false)
        val disconnected = off.copy(
            lifecycle = off.lifecycle.copy(systemProfile = SystemProfileState.DISCONNECTED),
        )

        assertEquals(true, FitClipGameModeCardPolicy.request(off, gameMode = true))
        assertNull(FitClipGameModeCardPolicy.request(off, gameMode = false))
        assertNull(FitClipGameModeCardPolicy.request(disconnected, gameMode = true))
    }

    private fun connectedState(gameMode: Boolean?): EarbudState {
        val runtime = AdapterRuntimeState().let { state ->
            if (gameMode == null) state else state.copy(
                features = state.features.update(EdifierGameModeFeatureState(gameMode)),
            )
        }
        return EarbudState(
            lifecycle = DeviceLifecycle(
                systemProfile = SystemProfileState.CONNECTED,
                privateTransport = PrivateTransportState.CONNECTED,
                protocolHandshake = ProtocolHandshakeState.CONFIRMED,
            ),
            features = runtime.features,
        )
    }
}
