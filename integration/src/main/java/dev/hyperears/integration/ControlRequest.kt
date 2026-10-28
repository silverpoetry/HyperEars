package dev.hyperears.integration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * A semantic control request for one active headset session.
 *
 * Requests are platform-neutral. The framework serializes them at the MiLink-to-Bluetooth
 * boundary and restores the same typed object before Adapter validation and protocol encoding.
 */
@Serializable
sealed interface ControlRequest

/**
 * Common controls inherited by every headset Adapter.
 *
 * Vendor or model request families remain siblings under [ControlRequest]. This keeps the standard
 * baseline stable while allowing a family to add precise request types without extending global
 * enums or IPC fields.
 */
@Serializable
sealed interface StandardControlRequest : ControlRequest {
    @Serializable
    @SerialName("standard.begin_vendor_handoff")
    data class BeginVendorHandoff(val packageName: String, val leaseId: String) : StandardControlRequest

    @Serializable
    @SerialName("standard.end_vendor_handoff")
    data class EndVendorHandoff(val leaseId: String) : StandardControlRequest

    @Serializable
    @SerialName("standard.refresh")
    data object Refresh : StandardControlRequest

    @Serializable
    @SerialName("standard.set_noise_mode")
    data class SetNoiseMode(
        val mode: NoiseMode,
    ) : StandardControlRequest
}

/**
 * Adapter-owned execution behavior for one request.
 *
 * The policy is request-scoped so vendor controls such as ANC depth can use the same confirmation,
 * optimistic-state and rate-limit machinery as the standard noise-mode request.
 */
data class ControlExecutionPolicy(
    val confirmation: ControlConfirmationPolicy = ControlConfirmationPolicy.DEVICE_REPORT,
    val cooldownMs: Long = 0L,
    val readbackDelayMs: Long = DEFAULT_READBACK_DELAY_MS,
    val stateAfterWrite: DeviceFeatureState? = null,
    val commandGapMs: Long = DEFAULT_COMMAND_GAP_MS,
) {
    init {
        require(cooldownMs >= 0L) { "Control cooldown cannot be negative" }
        require(readbackDelayMs >= 0L) { "Control readback delay cannot be negative" }
        require(commandGapMs >= 0L) { "Control command gap cannot be negative" }
        require(
            confirmation == ControlConfirmationPolicy.DEVICE_REPORT || stateAfterWrite != null,
        ) { "Optimistic control policies require a state to publish" }
    }

    companion object {
        const val DEFAULT_READBACK_DELAY_MS = 120L
        const val DEFAULT_COMMAND_GAP_MS = 120L
    }
}

/** Declares the requests that one Adapter may execute in its current confirmed state. */
fun interface ControlRequestContract {
    fun supports(adapter: EarbudAdapter, request: ControlRequest): Boolean
}

/** Baseline request contract inherited by all adapters. */
object StandardControlRequestContract : ControlRequestContract {
    override fun supports(adapter: EarbudAdapter, request: ControlRequest): Boolean = when {
        request is StandardControlRequest.BeginVendorHandoff ->
            request.leaseId.isNotBlank() && adapter.controlApps.any { it.packageName == request.packageName }
        request is StandardControlRequest.EndVendorHandoff -> request.leaseId.isNotBlank()
        request === StandardControlRequest.Refresh -> true
        request is StandardControlRequest.SetNoiseMode ->
            adapter.effectiveCapabilities().noiseControl &&
                request.mode in adapter.effectiveSupportedNoiseModes()

        else -> false
    }
}

/** Adds a family or model request predicate without weakening inherited standard controls. */
fun ControlRequestContract.extending(
    additionalSupport: (EarbudAdapter, ControlRequest) -> Boolean,
): ControlRequestContract = ControlRequestContract { adapter, request ->
    supports(adapter, request) || additionalSupport(adapter, request)
}

/** Private, versioned request envelope used only at the process boundary. */
@Serializable
private data class ControlEnvelope(
    val schemaVersion: Int,
    val request: ControlRequest,
)

/**
 * Framework-owned serialization for [ControlRequest].
 *
 * Adapter and card authors create typed request objects only. They never construct JSON, Intent
 * extras, discriminators, or transport envelopes themselves.
 */
object ControlRequestTransport {
    private const val SCHEMA_VERSION = 1
    private const val MAX_ENCODED_BYTES = 4 * 1024

    private val json = Json {
        serializersModule = SerializersModule {
            polymorphic(ControlRequest::class) {
                subclass(RoseRsV2ControlRequest.SetEqualizer::class, RoseRsV2ControlRequest.SetEqualizer.serializer())
                subclass(RoseRsV2ControlRequest.SetBass::class, RoseRsV2ControlRequest.SetBass.serializer())
                subclass(RoseRsV2ControlRequest.SetGame::class, RoseRsV2ControlRequest.SetGame.serializer())
                subclass(RoseRsV2ControlRequest.SetMultipoint::class, RoseRsV2ControlRequest.SetMultipoint.serializer())
                subclass(RoseRsV2ControlRequest.SetAudio::class, RoseRsV2ControlRequest.SetAudio.serializer())
                subclass(RoseRsV2ControlRequest.SetTouchEnabled::class, RoseRsV2ControlRequest.SetTouchEnabled.serializer())
                subclass(RoseRsV2ControlRequest.SetTouch::class, RoseRsV2ControlRequest.SetTouch.serializer())
                subclass(RoseRsV2ControlRequest.SetPrompt::class, RoseRsV2ControlRequest.SetPrompt.serializer())
                subclass(RoseRsV2ControlRequest.Find::class, RoseRsV2ControlRequest.Find.serializer())
                subclass(PuddingControlRequest.SetValue::class, PuddingControlRequest.SetValue.serializer())
                subclass(TechnicsControlRequest.SetLevel::class, TechnicsControlRequest.SetLevel.serializer())
                subclass(SonyControlRequest.SetDsee::class, SonyControlRequest.SetDsee.serializer())
                subclass(SonyControlRequest.SetConnectionQuality::class, SonyControlRequest.SetConnectionQuality.serializer())
                subclass(SonyControlRequest.SetSpeakToChat::class, SonyControlRequest.SetSpeakToChat.serializer())
                subclass(SamsungDailyControlRequest.SetEqualizer::class, SamsungDailyControlRequest.SetEqualizer.serializer())
                subclass(SamsungDailyControlRequest.SetAmbientVolume::class, SamsungDailyControlRequest.SetAmbientVolume.serializer())
                subclass(SamsungDailyControlRequest.SetAncLevel::class, SamsungDailyControlRequest.SetAncLevel.serializer())
                subclass(SamsungDailyControlRequest.SetCustomAmbient::class, SamsungDailyControlRequest.SetCustomAmbient.serializer())
                subclass(SamsungDailyControlRequest.SetCustomEqualizer::class, SamsungDailyControlRequest.SetCustomEqualizer.serializer())
                subclass(SamsungDailyControlRequest.SetLegacyLock::class, SamsungDailyControlRequest.SetLegacyLock.serializer())
                subclass(SamsungDailyControlRequest.SetLegacyHold::class, SamsungDailyControlRequest.SetLegacyHold.serializer())
                subclass(SamsungDailyControlRequest.SetVoiceFocus::class, SamsungDailyControlRequest.SetVoiceFocus.serializer())
                subclass(SamsungDailyControlRequest.Find::class, SamsungDailyControlRequest.Find.serializer())
                subclass(SamsungDailyControlRequest.MuteFind::class, SamsungDailyControlRequest.MuteFind.serializer())
                subclass(BoseDailyControlRequest.SetEqBand::class, BoseDailyControlRequest.SetEqBand.serializer())
                subclass(BoseDailyControlRequest.SetVoicePrompts::class, BoseDailyControlRequest.SetVoicePrompts.serializer())
                subclass(BoseDailyControlRequest.SetMultipoint::class, BoseDailyControlRequest.SetMultipoint.serializer())
                subclass(BoseDailyControlRequest.SetAutoPause::class, BoseDailyControlRequest.SetAutoPause.serializer())
                subclass(BoseDailyControlRequest.SetCncLevel::class, BoseDailyControlRequest.SetCncLevel.serializer())
                subclass(BoseDailyControlRequest.SetAnr::class, BoseDailyControlRequest.SetAnr.serializer())
                subclass(NiceHckControlRequest.SetValue::class, NiceHckControlRequest.SetValue.serializer())
                subclass(RoseRfcommControlRequest.SetValue::class, RoseRfcommControlRequest.SetValue.serializer())
                subclass(SonyControlRequest.SetEqPreset::class, SonyControlRequest.SetEqPreset.serializer())
                subclass(SonyControlRequest.SetEqBands::class, SonyControlRequest.SetEqBands.serializer())
                subclass(SonyControlRequest.SetClearBass::class, SonyControlRequest.SetClearBass.serializer())
                subclass(SonyControlRequest.SetAmbient::class, SonyControlRequest.SetAmbient.serializer())
                subclass(HuaweiDailyControlRequest.SetWearDetection::class, HuaweiDailyControlRequest.SetWearDetection.serializer())
                subclass(HuaweiDailyControlRequest.SetMultipoint::class, HuaweiDailyControlRequest.SetMultipoint.serializer())
                subclass(HuaweiDailyControlRequest.SetEqualizer::class, HuaweiDailyControlRequest.SetEqualizer.serializer())
                subclass(HuaweiDailyControlRequest.SetCustomEqualizer::class, HuaweiDailyControlRequest.SetCustomEqualizer.serializer())
                subclass(HuaweiDailyControlRequest.SetGesture::class, HuaweiDailyControlRequest.SetGesture.serializer())
                subclass(OppoControlRequest.SetToggle::class, OppoControlRequest.SetToggle.serializer())
                subclass(OppoControlRequest.SelectEqualizer::class, OppoControlRequest.SelectEqualizer.serializer())
                subclass(OppoControlRequest.UpdateEqualizer::class, OppoControlRequest.UpdateEqualizer.serializer())
                subclass(QcyControlRequest.SetToggle::class, QcyControlRequest.SetToggle.serializer())
                subclass(QcyControlRequest.SetSpatialMode::class, QcyControlRequest.SetSpatialMode.serializer())
                subclass(QcyControlRequest.SetEqPreset::class, QcyControlRequest.SetEqPreset.serializer())
                subclass(QcyControlRequest.SetCustomEq::class, QcyControlRequest.SetCustomEq.serializer())
                subclass(VivoTws3eControlRequest.SetDoubleTap::class, VivoTws3eControlRequest.SetDoubleTap.serializer())
                subclass(VivoTws3eControlRequest.SetHold::class, VivoTws3eControlRequest.SetHold.serializer())
                subclass(VivoTws3eControlRequest.SetDeepX::class, VivoTws3eControlRequest.SetDeepX.serializer())
                subclass(VivoTws3eControlRequest.SetWearDetection::class, VivoTws3eControlRequest.SetWearDetection.serializer())
                subclass(AppleHostLongPressLeaseRequest::class, AppleHostLongPressLeaseRequest.serializer())
                subclass(AppleSpatialControlRequest.SetMode::class, AppleSpatialControlRequest.SetMode.serializer())
                subclass(AppleSpatialControlRequest.BeginCalibration::class, AppleSpatialControlRequest.BeginCalibration.serializer())
                subclass(AppleSpatialControlRequest.CalibrationStep::class, AppleSpatialControlRequest.CalibrationStep.serializer())
                subclass(AppleSpatialControlRequest.Recenter::class, AppleSpatialControlRequest.Recenter.serializer())
                subclass(AppleControlRequest.SetLongPress::class, AppleControlRequest.SetLongPress.serializer())
                subclass(AppleControlRequest.SetListeningCycle::class, AppleControlRequest.SetListeningCycle.serializer())
                subclass(AppleControlRequest.SetEqualizer::class, AppleControlRequest.SetEqualizer.serializer())
                subclass(SamsungControlRequest.SetTouchGesture::class, SamsungControlRequest.SetTouchGesture.serializer())
                subclass(SamsungControlRequest.SetTouchHoldActions::class, SamsungControlRequest.SetTouchHoldActions.serializer())
                subclass(SamsungControlRequest.SetTouchHoldNoiseCycles::class, SamsungControlRequest.SetTouchHoldNoiseCycles.serializer())
                subclass(SamsungControlRequest.SetTouchHoldAction::class, SamsungControlRequest.SetTouchHoldAction.serializer())
                subclass(SamsungControlRequest.SetTouchHoldNoiseCycle::class, SamsungControlRequest.SetTouchHoldNoiseCycle.serializer())
            }
        }
        classDiscriminator = "command"
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    fun encode(request: ControlRequest): String {
        val encoded = json.encodeToString(
            ControlEnvelope(
                schemaVersion = SCHEMA_VERSION,
                request = request,
            ),
        )
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_ENCODED_BYTES) {
            "Control request exceeds the transport payload limit"
        }
        return encoded
    }

    fun decode(encoded: String): ControlRequest? {
        if (encoded.toByteArray(Charsets.UTF_8).size > MAX_ENCODED_BYTES) return null
        return runCatching {
            json.decodeFromString<ControlEnvelope>(encoded)
                .takeIf { it.schemaVersion == SCHEMA_VERSION }
                ?.request
        }.getOrNull()
    }
}
