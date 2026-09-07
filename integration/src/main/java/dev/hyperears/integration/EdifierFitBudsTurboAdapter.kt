package dev.hyperears.integration

/**
 * Concrete model adapter for Edifier FitBuds Turbo.
 *
 * FitBuds Turbo is a 2026 in-ear TWS with -49 dB ANC, wind-noise reduction, five noise modes
 * and a 45 ms low-latency game mode. It uses the shared Edifier BES SPP service
 * (`EDF00000-...`), so the standard family wire layer already carries transport discovery.
 *
 * Protocol assumptions (family extrapolation from FitClip Ultra / 花再 Evo Pro):
 *  - Battery is delivered by the TWS device-state command `0xF2` as independent left/right
 *    levels, projected to a TWS aggregate (same code path as FitClip Ultra and Evo Pro).
 *  - ANC is probed through the family dialect candidates. The adapter deliberately starts with
 *    all private capabilities locked: MiLink only exposes noise modes after a legal ANC
 *    response confirms the correct dialect, so an unknown ANC slot never mis-advertises.
 *  - Game mode uses the BES `0x08/0x09` low-latency family command.
 *
 * NOTE: The battery command (`0xD0` legacy vs `0xF2` device-state) and the exact ANC
 * dialect / game-mode support MUST be confirmed with an on-device capture before merging
 * (see docs/patch-NOTES.md). The initial capability gating keeps this adapter safe even if
 * the guesses are wrong.
 */
class EdifierFitBudsTurboAdapter : EdifierEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "Edifier FitBuds Turbo"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH

    override val miLinkCardPresentationId: MiLinkCardPresentationId?
        get() = EdifierMiLinkPresentationIds.FITBUDS_TURBO.takeIf {
            // FitBuds Turbo is an ANC model with a 45 ms game mode. The dedicated card shows the
            // ANC four modes (ANC/off/transparency/wind) plus a game-mode switch. It is exposed
            // once the ANC dialect is protocol-confirmed (wind-noise control implies the four-mode
            // family) and the game-mode feature has been observed on the wire.
            effectiveCapabilities().windNoiseControl &&
                runtimeState().features.get<EdifierGameModeFeatureState>() != null
        }

    override val featureStateContract: DeviceFeatureStateContract =
        StandardDeviceFeatureStateContract.extending { _, state ->
            state is EdifierGameModeFeatureState
        }

    override val controlRequestContract: ControlRequestContract =
        StandardControlRequestContract.extending { adapter, request ->
            request is EdifierControlRequest.SetGameMode &&
                adapter.runtimeState().features.get<EdifierGameModeFeatureState>() != null
        }

    override val wireConfig: EdifierWireConfig = EdifierWireConfig(
        batteryQueries = listOf(EdifierBatteryQuery.DEVICE_STATE),
        batteryProjection = EdifierBatteryProjection.TWS_AGGREGATE,
        // FitBuds Turbo supports ANC + transparency + wind. Probe the family dialect candidates
        // so the exact ANC slot/value mapping is confirmed on-device rather than hard-coded.
        ancDialects = listOf(
            EdifierAncDialects.W860_NB_PRO,
            EdifierAncDialects.EVO_PRO,
        ),
        // 45 ms low-latency mode: probe the BES game-mode family command.
        gameModeQuery = true,
        // FitBuds Turbo answers the private protocol with PLAINTEXT payloads (no XOR 0xA5) and
        // accepts plaintext set commands. Confirmed on-device: ANC query returns `1B 06` verbatim.
        plaintextPayloads = true,
    )

    override fun controlPolicy(request: ControlRequest): ControlExecutionPolicy =
        super.controlPolicy(request).let { policy ->
            when (request) {
                is EdifierControlRequest.SetGameMode ->
                    ControlExecutionPolicy(
                        confirmation = ControlConfirmationPolicy.DEVICE_REPORT,
                    )

                is StandardControlRequest.SetNoiseMode ->
                    policy.copy(
                        confirmation = ControlConfirmationPolicy.PUBLISH_AFTER_WRITE,
                    )

                else -> policy
            }
        }

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!identity.standardHeadset || identity.nativeSystemEarbud) return false
        return normalizeDeviceName(identity.deviceName.orEmpty()) in MODEL_NAMES
    }

    companion object {
        const val ID = "edifier-fitbuds-turbo"

        /** Normalized Bluetooth names (lowercase, alphanumeric only) that select this model. */
        private val MODEL_NAMES = setOf(
            "edifierfitbudsturbo",
            "fitbudsturbo",
        )
    }
}
