package dev.hyperears.hook

import dev.hyperears.integration.MiLinkCardPresentationId
import dev.hyperears.integration.SennheiserEarbudAdapter

/** Protocol-confirmed anti-wind option for the MOMENTUM 4 ANC card. */
internal object SennheiserMomentum4MiLinkCardAdapter : WindNoiseToggleMiLinkCardAdapter(
    presentationId = MiLinkCardPresentationId(
        SennheiserEarbudAdapter.MOMENTUM_4_PRESENTATION_ID,
    ),
    modelLabel = "MOMENTUM 4",
)
