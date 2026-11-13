package dev.hyperears.hook

import dev.hyperears.integration.SwarmooMo3ProAdapter

/** Uses the existing native three-mode card and capability-gated wind switch. */
internal object SwarmooMo3MiLinkCardAdapter : WindNoiseToggleMiLinkCardAdapter(
    presentationId = SwarmooMo3ProAdapter.PRESENTATION_ID,
    modelLabel = "SWARMOO MO3 PRO",
)
