package dev.hyperears.hook

import dev.hyperears.integration.RoseLegacyAdapter

internal object RoseLegacyMiLinkCardAdapter:NativeAncToggleMiLinkCardAdapter(
    RoseLegacyAdapter.PRESENTATION,"ROSE",listOf(WindNoiseToggleSpec,RoseLegacyDetailsSpec))
