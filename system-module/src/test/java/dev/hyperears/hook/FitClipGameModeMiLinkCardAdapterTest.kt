package dev.hyperears.hook

import dev.hyperears.integration.AdapterRuntimeState
import dev.hyperears.integration.DeviceLifecycle
import dev.hyperears.integration.EdifierGameModeFeatureState
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.PrivateTransportState
import dev.hyperears.integration.ProtocolHandshakeState
import dev.hyperears.integration.SystemProfileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FitClipGameModeMiLinkCardAdapterTest {
    @Test
    fun confirmedFeatureEnablesTheToggleAndProjectsTheReportedValue() {
        val enabled = connectedState(gameMode = true)
        val disabled = connectedState(gameMode = false)

        assertEquals(
            FitClipGameModeTogglePolicy.ToggleState(checked = true, enabled = true),
            FitClipGameModeTogglePolicy.render(enabled),
        )
        assertEquals(
            FitClipGameModeTogglePolicy.ToggleState(checked = false, enabled = true),
            FitClipGameModeTogglePolicy.render(disabled),
        )
    }

    @Test
    fun missingProtocolEvidenceNeverCreatesAnActionableControl() {
        val state = connectedState(gameMode = null)

        assertFalse(FitClipGameModeTogglePolicy.render(state).enabled)
        assertNull(FitClipGameModeTogglePolicy.request(state, checked = true))
    }

    @Test
    fun onlyAConnectedSessionMaySendARealStateTransition() {
        val off = connectedState(gameMode = false)
        val disconnected = off.copy(
            lifecycle = off.lifecycle.copy(systemProfile = SystemProfileState.DISCONNECTED),
        )

        assertEquals(true, FitClipGameModeTogglePolicy.request(off, checked = true))
        assertNull(FitClipGameModeTogglePolicy.request(off, checked = false))
        assertNull(FitClipGameModeTogglePolicy.request(disconnected, checked = true))
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
