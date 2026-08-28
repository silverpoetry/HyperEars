package dev.hyperears.hook

import dev.hyperears.integration.BoseQuietComfortHeadphonesAdapter
import dev.hyperears.integration.BoseMiLinkPresentationIds
import dev.hyperears.integration.EdifierMiLinkPresentationIds
import dev.hyperears.integration.MiLinkCardPresentationId
import dev.hyperears.integration.NiceHckYuanDaoOrigAdapter
import dev.hyperears.integration.NoiseMode
import dev.hyperears.integration.RoseBudsFeelMk2Adapter
import dev.hyperears.integration.RoseEarfreeI5Adapter
import dev.hyperears.integration.StarRingUltraAdapter
import dev.hyperears.integration.SonyMiLinkPresentationIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
            SonyAmbientOnlyMiLinkCardAdapter,
            MiLinkCardAdapterRegistry.resolve(SonyMiLinkPresentationIds.AMBIENT_ONLY),
        )
        assertSame(
            FitClipUltraGameModeMiLinkCardAdapter,
            MiLinkCardAdapterRegistry.resolve(EdifierMiLinkPresentationIds.GAME_MODE),
        )
        assertNull(
            MiLinkCardAdapterRegistry.resolve(
                MiLinkCardPresentationId("unknown-model"),
            ),
        )
    }
}
