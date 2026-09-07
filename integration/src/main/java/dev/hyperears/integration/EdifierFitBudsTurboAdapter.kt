package dev.hyperears.integration

/** PR #62: exact FitBuds Turbo, plaintext BES payloads and the 0x1B noise dialect. */
class EdifierFitBudsTurboAdapter : EdifierGameModeAdapter() {
    override val id: String = ID
    override val displayName: String = "Edifier FitBuds Turbo"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH

    override val miLinkCardPresentationId: MiLinkCardPresentationId?
        get() = when {
            effectiveCapabilities().noiseControl -> EdifierMiLinkPresentationIds.FITBUDS_TURBO
            runtimeState().features.get<EdifierGameModeFeatureState>() != null ->
                EdifierMiLinkPresentationIds.GAME_MODE
            else -> null
        }

    override val wireConfig: EdifierWireConfig = EdifierWireConfig(
        batteryQueries = listOf(EdifierBatteryQuery.DEVICE_STATE),
        batteryProjection = EdifierBatteryProjection.TWS_AGGREGATE,
        ancDialects = listOf(EdifierAncDialects.EVO_PRO),
        gameModeQuery = true,
        plaintextPayloads = true,
        noiseModeReadback = true,
    )

    override fun controlPolicy(request: ControlRequest): ControlExecutionPolicy =
        super.controlPolicy(request).let { policy ->
            if (request is StandardControlRequest.SetNoiseMode) {
                policy.copy(confirmation = ControlConfirmationPolicy.PUBLISH_AFTER_WRITE_THEN_REFRESH)
            } else {
                policy
            }
        }

    override fun matches(identity: EarbudIdentity): Boolean =
        identity.standardHeadset && !identity.nativeSystemEarbud &&
            normalizeDeviceName(identity.deviceName.orEmpty()) in MODEL_NAMES

    companion object {
        const val ID = "edifier-fitbuds-turbo"
        private val MODEL_NAMES = setOf("edifierfitbudsturbo", "fitbudsturbo")
    }
}
