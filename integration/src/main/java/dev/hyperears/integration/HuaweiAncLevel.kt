package dev.hyperears.integration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Huawei FreeBuds Pro 3 ANC level extension.
 *
 * The vendor protocol carries mode-specific levels as a byte in the noise-mode write command and
 * the mode-state report. ANC levels are normal / comfort / ultra / dynamic; transparency levels
 * are voice-boost / normal. Level is a model-specific feature, so it is exposed as a typed
 * [DeviceFeatureState] and a typed [ControlRequest] sibling of the standard requests;
 * serialization, process transport and Adapter validation are handled by the v2.0.0 framework.
 */
@Serializable
enum class HuaweiAncLevel {
    @SerialName("normal")
    NORMAL,

    @SerialName("comfort")
    COMFORT,

    @SerialName("ultra")
    ULTRA,

    @SerialName("dynamic")
    DYNAMIC,

    @SerialName("voice_boost")
    VOICE_BOOST,

    @SerialName("trans_normal")
    TRANS_NORMAL,
}

/** Wire level byte for the `2B 04` level write: ANC `0..3`, transparency `1..2`. */
fun HuaweiAncLevel.toWireLevel(): Int = when (this) {
    HuaweiAncLevel.NORMAL -> 0
    HuaweiAncLevel.COMFORT -> 1
    HuaweiAncLevel.ULTRA -> 2
    HuaweiAncLevel.DYNAMIC -> 3
    HuaweiAncLevel.VOICE_BOOST -> 1
    HuaweiAncLevel.TRANS_NORMAL -> 2
}

/** The domain noise mode this level belongs to; the wire command is always `[activeMode, level]`. */
fun HuaweiAncLevel.domainNoiseMode(): NoiseMode = when (this) {
    HuaweiAncLevel.NORMAL,
    HuaweiAncLevel.COMFORT,
    HuaweiAncLevel.ULTRA,
    HuaweiAncLevel.DYNAMIC,
    -> NoiseMode.ANC

    HuaweiAncLevel.VOICE_BOOST,
    HuaweiAncLevel.TRANS_NORMAL,
    -> NoiseMode.TRANSPARENCY
}

/** Current and supported ANC level for the active session; null when the active mode carries none. */
@Serializable
@SerialName("huawei.freebuds_pro3.anc_level")
data class HuaweiAncLevelFeatureState(
    val current: HuaweiAncLevel?,
    val supported: Set<HuaweiAncLevel>,
) : DeviceFeatureState {
    @Transient
    override val featureId: String = FEATURE_ID

    companion object {
        const val FEATURE_ID = "huawei.freebuds_pro3.anc_level"
    }
}

/** Model-owned control requests for the Huawei FreeBuds Pro 3. */
@Serializable
sealed interface HuaweiControlRequest : ControlRequest {
    @Serializable
    @SerialName("huawei.freebuds_pro3.set_anc_level")
    data class SetAncLevel(
        val level: HuaweiAncLevel,
    ) : HuaweiControlRequest
}
