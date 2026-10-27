package dev.hyperears.hook

import dev.hyperears.integration.AdapterRuntimeState
import dev.hyperears.integration.DeviceLifecycle
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.FloS2ControlRequest
import dev.hyperears.integration.FloS2MiLinkPresentationIds
import dev.hyperears.integration.FloS2SoundEffect
import dev.hyperears.integration.FloS2SoundEffectFeatureState
import dev.hyperears.integration.MiLinkCardPresentationId
import dev.hyperears.integration.NoiseMode
import dev.hyperears.integration.PrivateTransportState
import dev.hyperears.integration.ProtocolHandshakeState
import dev.hyperears.integration.SystemProfileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class FloS2SoundEffectMiLinkCardAdapterTest {
    @Test
    fun soundEffectPresentationUsesTheStockThreeStateSurface() {
        assertEquals(
            MiLinkNativeCardSurface.ANC_THREE_STATE,
            FloS2SoundEffectMiLinkCardAdapter.nativeSurface,
        )
        assertEquals(
            NoiseMode.ANC,
            FloS2SoundEffectMiLinkCardAdapter.nativeSurfaceNoiseMode(
                connectedState(effect = FloS2SoundEffect.STANDARD),
            ),
        )
        assertNull(
            FloS2SoundEffectMiLinkCardAdapter.nativeSurfaceNoiseMode(
                connectedState(effect = null),
            ),
        )
    }

    @Test
    fun registryResolvesTheFloS2Presentation() {
        assertSame(
            FloS2SoundEffectMiLinkCardAdapter,
            MiLinkCardAdapterRegistry.resolve(FloS2MiLinkPresentationIds.SOUND_EFFECT),
        )
        assertNull(
            MiLinkCardAdapterRegistry.resolve(MiLinkCardPresentationId("unknown-model")),
        )
    }

    @Test
    fun confirmedFeatureEnablesTheSelectorAndProjectsTheReportedValue() {
        val standard = connectedState(FloS2SoundEffect.STANDARD)
        val enhanced = connectedState(FloS2SoundEffect.ENHANCED)

        assertEquals(
            FloS2SoundEffectCardPolicy.CardState(effect = FloS2SoundEffect.STANDARD, enabled = true),
            FloS2SoundEffectCardPolicy.render(standard),
        )
        assertEquals(
            FloS2SoundEffectCardPolicy.CardState(effect = FloS2SoundEffect.ENHANCED, enabled = true),
            FloS2SoundEffectCardPolicy.render(enhanced),
        )
    }

    @Test
    fun missingProtocolEvidenceNeverCreatesAnActionableControl() {
        val state = connectedState(effect = null)

        assertFalse(FloS2SoundEffectCardPolicy.render(state).enabled)
        assertNull(FloS2SoundEffectCardPolicy.request(state, FloS2SoundEffect.SWIMMING))
    }

    @Test
    fun onlyAConnectedSessionMaySendARealStateTransition() {
        val standard = connectedState(FloS2SoundEffect.STANDARD)
        val disconnected = standard.copy(
            lifecycle = standard.lifecycle.copy(systemProfile = SystemProfileState.DISCONNECTED),
        )

        assertEquals(
            FloS2SoundEffect.ENHANCED,
            FloS2SoundEffectCardPolicy.request(standard, FloS2SoundEffect.ENHANCED),
        )
        assertNull(FloS2SoundEffectCardPolicy.request(standard, FloS2SoundEffect.STANDARD))
        assertNull(FloS2SoundEffectCardPolicy.request(disconnected, FloS2SoundEffect.ENHANCED))
    }

    private fun connectedState(effect: FloS2SoundEffect?): EarbudState {
        val runtime = AdapterRuntimeState().let { state ->
            if (effect == null) {
                state
            } else {
                state.copy(
                    features = state.features.update(FloS2SoundEffectFeatureState(effect)),
                )
            }
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
