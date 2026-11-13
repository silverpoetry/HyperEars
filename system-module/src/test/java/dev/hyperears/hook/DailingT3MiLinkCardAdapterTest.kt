package dev.hyperears.hook

import dev.hyperears.integration.AdapterRuntimeState
import dev.hyperears.integration.ControlAppCatalog
import dev.hyperears.integration.ControlOwnership
import dev.hyperears.integration.DailingControlRequest
import dev.hyperears.integration.DailingGameModeFeatureState
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
    private fun state(enabled: Boolean? = false): EarbudState {
        val features = AdapterRuntimeState().features.let {
            if (enabled == null) it else it.update(DailingGameModeFeatureState(enabled))
        }
        return EarbudState(
            lifecycle = DeviceLifecycle(
                systemProfile = SystemProfileState.CONNECTED,
                privateTransport = PrivateTransportState.CONNECTED,
                protocolHandshake = ProtocolHandshakeState.CONFIRMED,
            ),
            features = features.update(NoiseModeFeatureState(NoiseMode.ANC)),
        )
    }

    @Test fun registryUsesExistingAncExtension() {
        assertSame(DailingT3CardAdapter, MiLinkCardAdapterRegistry.resolve(DailingMiLinkPresentationIds.CARD))
        assertEquals(MiLinkNativeCardSurface.NONE, DailingT3CardAdapter.nativeSurface)
    }

    @Test fun gameToggleRequiresItsOwnReport() {
        assertFalse(DailingGameModeToggleSpec.render(state(null)).available)
        assertNull(DailingGameModeToggleSpec.request(state(null), true))
        assertEquals(DailingControlRequest.SetGameMode(true), DailingGameModeToggleSpec.request(state(), true))
        assertNull(DailingGameModeToggleSpec.request(state(true), true))
    }

    @Test fun connectionAndOwnershipGateClicksWithoutLosingDisplayedState() {
        val connected = state(true)
        val paused = listOf(
            connected.lifecycle.copy(
                controlOwnership = ControlOwnership.EXTERNAL_APP,
                externalControlApp = ControlAppCatalog.dailing,
            ),
            connected.lifecycle.copy(privateTransport = PrivateTransportState.IDLE),
            connected.lifecycle.copy(privateTransport = PrivateTransportState.NOT_REQUIRED),
            connected.lifecycle.copy(protocolHandshake = ProtocolHandshakeState.PENDING),
            connected.lifecycle.copy(systemProfile = SystemProfileState.DISCONNECTED),
        )
        paused.forEach {
            val current = connected.copy(lifecycle = it)
            assertTrue(DailingGameModeToggleSpec.render(current).checked)
            assertFalse(DailingGameModeToggleSpec.render(current).enabled)
            assertNull(DailingGameModeToggleSpec.request(current, false))
        }
    }
}
