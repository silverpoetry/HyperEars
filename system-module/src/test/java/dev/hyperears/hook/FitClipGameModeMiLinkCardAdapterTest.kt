package dev.hyperears.hook

import dev.hyperears.integration.AdapterSnapshot
import dev.hyperears.integration.EarbudCapabilities
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.DeviceLifecycle
import dev.hyperears.integration.GameModeFeatureState
import dev.hyperears.integration.PrivateTransportState
import dev.hyperears.integration.ProtocolHandshakeState
import dev.hyperears.integration.SystemProfileState
import dev.hyperears.integration.withFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FitClipGameModeMiLinkCardAdapterTest {
    private val connected = EarbudState(
        lifecycle = DeviceLifecycle(
            SystemProfileState.CONNECTED,
            PrivateTransportState.NOT_REQUIRED,
            ProtocolHandshakeState.NOT_REQUIRED,
        ),
        adapter = AdapterSnapshot(
            id = "edifier-fitclip-ultra",
            displayName = "Edifier FitClip Ultra",
            resolution = dev.hyperears.integration.AdapterResolution.EXACT_MATCH,
            privateProtocolRequired = true,
            batterySource = dev.hyperears.integration.BatterySource.PRIVATE_PROTOCOL,
            formFactor = dev.hyperears.integration.HeadsetFormFactor.TWS,
            capabilities = EarbudCapabilities(gameModeControl = true),
            supportedNoiseModes = emptySet(),
            presentationId = dev.hyperears.integration.EdifierMiLinkPresentationIds.GAME_MODE,
            transportKinds = setOf(dev.hyperears.integration.TransportKind.RFCOMM),
        ),
    )

    @Test
    fun gameModeSwitchRendersOnlyWhenCapabilityIsConfirmed() {
        val on = GameModeToggleControlPolicy.render(connected.withFeature(GameModeFeatureState(enabled = true)))
        val off = GameModeToggleControlPolicy.render(connected.withFeature(GameModeFeatureState(enabled = false)))

        assertTrue(on.enabled)
        assertTrue(on.checked)
        assertTrue(off.enabled)
        assertFalse(off.checked)
    }

    @Test
    fun gameModeToggleIsDisabledWithoutConfirmedCapability() {
        val noCapability = connected.copy(
            adapter = connected.adapter?.copy(capabilities = EarbudCapabilities(gameModeControl = false)),
        ).withFeature(GameModeFeatureState(enabled = true))

        val state = GameModeToggleControlPolicy.render(noCapability)
        assertFalse(state.enabled)
        assertNull(GameModeToggleControlPolicy.request(noCapability, checked = true))
    }

    @Test
    fun gameModeToggleIsDisabledOutsideLiveSession() {
        val disconnected = connected.copy(lifecycle = DeviceLifecycle())
            .withFeature(GameModeFeatureState(enabled = true))

        assertFalse(GameModeToggleControlPolicy.render(disconnected).enabled)
        assertNull(GameModeToggleControlPolicy.request(disconnected, checked = true))
    }

    @Test
    fun gameModeToggleRequestsOnlyOnEdgeTransition() {
        val on = connected.withFeature(GameModeFeatureState(enabled = true))
        val off = connected.withFeature(GameModeFeatureState(enabled = false))

        assertEquals(true, GameModeToggleControlPolicy.request(off, checked = true))
        assertEquals(false, GameModeToggleControlPolicy.request(on, checked = false))
        assertNull(GameModeToggleControlPolicy.request(on, checked = true))
        assertNull(GameModeToggleControlPolicy.request(off, checked = false))
    }
}
