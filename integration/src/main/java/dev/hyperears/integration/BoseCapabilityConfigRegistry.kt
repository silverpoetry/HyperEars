package dev.hyperears.integration

/** A BMAP noise-control dialect established from a successful read-only status response. */
internal enum class BoseDiscoveredDialect {
    AUDIO_MODES,
    ANR,
    CNC,
}

/** Internal Bose adapter configurations selected by authoritative wire evidence. */
internal object BoseCapabilityConfigRegistry {
    private data class ConfigTemplate(
        val suffix: String,
        val label: String,
        val control: BoseNoiseControlConfig,
        val presentationId: MiLinkCardPresentationId?,
    )

    private data class Definition(
        val formFactor: HeadsetFormFactor,
        val displayName: String,
        val configuration: BoseWireConfig,
        val presentationId: MiLinkCardPresentationId?,
    )

    private val templates = mapOf(
        BoseDiscoveredDialect.AUDIO_MODES to ConfigTemplate(
            suffix = "audio-modes",
            label = "Quiet/Aware",
            control = BoseNoiseControlConfig.AudioModes(
                supportedModes = setOf(NoiseMode.ANC, NoiseMode.TRANSPARENCY),
            ),
            presentationId = BoseMiLinkPresentationIds.TWO_MODE,
        ),
        BoseDiscoveredDialect.ANR to ConfigTemplate(
            suffix = "anr",
            label = "ANR",
            control = BoseNoiseControlConfig.Anr(),
            presentationId = BoseMiLinkPresentationIds.WIND_REPLACES_TRANSPARENCY,
        ),
        BoseDiscoveredDialect.CNC to ConfigTemplate(
            suffix = "cnc",
            label = "CNC",
            control = BoseNoiseControlConfig.Cnc(),
            presentationId = null,
        ),
    )

    private val definitions: List<Definition> = buildList {
        listOf(HeadsetFormFactor.TWS, HeadsetFormFactor.HEADPHONES).forEach { formFactor ->
            templates.values.forEach { template ->
                val formSuffix = when (formFactor) {
                    HeadsetFormFactor.TWS -> "earbuds"
                    HeadsetFormFactor.HEADPHONES -> "headphones"
                    HeadsetFormFactor.GLASSES -> error("Bose glasses are not registered")
                }
                val id = "bose-discovered-$formSuffix-${template.suffix}"
                val configuration = BoseWireConfig(
                    productId = null,
                    modelId = id,
                    noiseControl = template.control,
                )
                val displayName = "Bose $formSuffix (${template.label})"
                add(
                    Definition(
                        formFactor = formFactor,
                        displayName = displayName,
                        configuration = configuration,
                        presentationId = template.presentationId,
                    ),
                )
            }
        }
    }

    private val configurations = definitions.associate { definition ->
        (definition.formFactor to dialectOf(requireNotNull(definition.configuration.noiseControl))) to
            definition.configuration
    }

    init {
        require(
            configurations.size ==
                2 * BoseDiscoveredDialect.entries.size,
        )
    }

    fun config(
        formFactor: HeadsetFormFactor,
        dialect: BoseDiscoveredDialect,
        cncMaximumRawLevel: Int? = null,
    ): BoseWireConfig {
        val configuration = requireNotNull(configurations[formFactor to dialect])
        if (dialect != BoseDiscoveredDialect.CNC || cncMaximumRawLevel == null) {
            return configuration
        }
        return configuration.copy(
            noiseControl = BoseNoiseControlConfig.Cnc(
                maximumRawLevel = cncMaximumRawLevel,
            ),
        )
    }

    fun displayName(formFactor: HeadsetFormFactor, configuration: BoseWireConfig): String {
        val definition = definitions.first { it.configuration.modelId == configuration.modelId }
        return definition.displayName
    }

    fun presentationId(configuration: BoseWireConfig): MiLinkCardPresentationId? =
        definitions.firstOrNull { it.configuration.modelId == configuration.modelId }
            ?.presentationId

    private fun dialectOf(configuration: BoseNoiseControlConfig): BoseDiscoveredDialect =
        when (configuration) {
            is BoseNoiseControlConfig.AudioModes -> BoseDiscoveredDialect.AUDIO_MODES
            is BoseNoiseControlConfig.Anr -> BoseDiscoveredDialect.ANR
            is BoseNoiseControlConfig.Cnc -> BoseDiscoveredDialect.CNC
        }
}
