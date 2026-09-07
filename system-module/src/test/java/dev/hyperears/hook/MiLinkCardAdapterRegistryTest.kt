package dev.hyperears.hook

import dev.hyperears.integration.BoseQuietComfortHeadphonesAdapter
import dev.hyperears.integration.BoseMiLinkPresentationIds
import dev.hyperears.integration.DeviceLifecycle
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.EdifierGameModeFeatureState
import dev.hyperears.integration.EdifierMiLinkPresentationIds
import dev.hyperears.integration.MiLinkCardPresentationId
import dev.hyperears.integration.NiceHckYuanDaoOrigAdapter
import dev.hyperears.integration.NoiseMode
import dev.hyperears.integration.PrivateTransportState
import dev.hyperears.integration.ProtocolHandshakeState
import dev.hyperears.integration.RoseBudsFeelMk2Adapter
import dev.hyperears.integration.RoseEarfreeI5Adapter
import dev.hyperears.integration.StarRingUltraAdapter
import dev.hyperears.integration.SystemProfileState
import dev.hyperears.integration.SonyMiLinkPresentationIds
import dev.hyperears.integration.withFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MiLinkCardAdapterRegistryTest {
    @Test
    fun defaultExtendedModeRemainsInTheNativeAncBranch() {
        assertEquals(
            NoiseMode.ANC,
            StarRingUltraMiLinkCardAdapter.projectNativeNoiseMode(NoiseMode.WIND),
        )
    }

    @Test
    fun resolvesOnlyRegisteredConcreteModelPresentations() {
        assertSame(
            StarRingUltraMiLinkCardAdapter,
            MiLinkCardAdapterRegistry.resolve(StarRingUltraAdapter.PRESENTATION_ID),
        )
        assertSame(
            RoseEarfreeI5MiLinkCardAdapter,
            MiLinkCardAdapterRegistry.resolve(RoseEarfreeI5Adapter.PRESENTATION_ID),
        )
        assertSame(
            RoseBudsFeelMk2MiLinkCardAdapter,
            MiLinkCardAdapterRegistry.resolve(RoseBudsFeelMk2Adapter.PRESENTATION_ID),
        )
        assertSame(
            NiceHckOrigMiLinkCardAdapter,
            MiLinkCardAdapterRegistry.resolve(NiceHckYuanDaoOrigAdapter.PRESENTATION_ID),
        )
        assertSame(
            BoseQuietComfortMiLinkCardAdapter,
            MiLinkCardAdapterRegistry.resolve(
                BoseQuietComfortHeadphonesAdapter.PRESENTATION_ID,
            ),
        )
        assertSame(
            BoseAnrMiLinkCardAdapter,
            MiLinkCardAdapterRegistry.resolve(
                BoseMiLinkPresentationIds.WIND_REPLACES_TRANSPARENCY,
            ),
        )
        assertSame(
            BoseTwoModeMiLinkCardAdapter,
            MiLinkCardAdapterRegistry.resolve(BoseMiLinkPresentationIds.TWO_MODE),
        )
        assertSame(
            EdifierFourModeMiLinkCardAdapter,
            MiLinkCardAdapterRegistry.resolve(
                EdifierMiLinkPresentationIds.FOUR_MODE,
            ),
        )
        assertSame(
            EdifierFitBudsTurboMiLinkCardAdapter,
            MiLinkCardAdapterRegistry.resolve(EdifierMiLinkPresentationIds.FITBUDS_TURBO),
        )
        assertSame(
            FitClipUltraGameModeMiLinkCardAdapter,
            MiLinkCardAdapterRegistry.resolve(EdifierMiLinkPresentationIds.GAME_MODE),
        )
        assertSame(
            SonyAmbientOnlyMiLinkCardAdapter,
            MiLinkCardAdapterRegistry.resolve(SonyMiLinkPresentationIds.AMBIENT_ONLY),
        )
        assertNull(
            MiLinkCardAdapterRegistry.resolve(
                MiLinkCardPresentationId("unknown-model"),
            ),
        )
    }

    @Test
    fun fitBudsTurboGameModeDisabledUntilFeatureObserved() {
        val idle = EarbudState(
            lifecycle = DeviceLifecycle(
                SystemProfileState.CONNECTED,
                PrivateTransportState.NOT_REQUIRED,
                ProtocolHandshakeState.NOT_REQUIRED,
            ),
        )
        val idleToggle = FitBudsTurboGameModePolicy.render(idle)
        assertFalse("game switch must be disabled without the feature", idleToggle.enabled)
    }

    @Test
    fun fitBudsTurboGameModeTracksFeatureAndRequestsToggle() {
        val active = EarbudState(
            lifecycle = DeviceLifecycle(
                SystemProfileState.CONNECTED,
                PrivateTransportState.NOT_REQUIRED,
                ProtocolHandshakeState.NOT_REQUIRED,
            ),
        )
        val gameOff = active.withFeature(EdifierGameModeFeatureState(enabled = false))
        val offToggle = FitBudsTurboGameModePolicy.render(gameOff)
        assertTrue(offToggle.enabled)
        assertFalse(offToggle.checked)
        // Requesting game mode on while it is off should yield true (send a command).
        assertEquals(true, FitBudsTurboGameModePolicy.request(gameOff, checked = true))
        // Requesting the current state must be a no-op.
        assertNull(FitBudsTurboGameModePolicy.request(gameOff, checked = false))

        val gameOn = active.withFeature(EdifierGameModeFeatureState(enabled = true))
        assertTrue(FitBudsTurboGameModePolicy.render(gameOn).checked)
        assertEquals(false, FitBudsTurboGameModePolicy.request(gameOn, checked = false))
        assertNull(FitBudsTurboGameModePolicy.request(gameOn, checked = true))
    }
}
