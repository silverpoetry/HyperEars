package dev.hyperears.hook

import dev.hyperears.integration.MoondropPuddingAdapter

internal object MoondropPuddingMiLinkCardAdapter : WindNoiseToggleMiLinkCardAdapter(
    presentationId = MoondropPuddingAdapter.PRESENTATION_ID,
    modelLabel = "MOONDROP Pudding",
)
