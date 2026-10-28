package dev.hyperears.integration

import dev.hyperears.protocol.qcy.QcyWireCodec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class QcyToggle(val command: Int) {
    LOW_LATENCY(9), SLEEP(16), LDAC(35), MULTIPOINT(36), DYNAMIC_EQ(39), ADAPTIVE_VOLUME(64);
    internal fun wire() = QcyWireCodec.Toggle.entries.single { it.command == command }
}

@Serializable
data class QcyEqBand(val frequency: Int, val gainHundredths: Int, val qHundredths: Int, val type: Int)

@Serializable
data class QcyEqualizer(val preset: Int, val masterGainHundredths: Int, val bands: List<QcyEqBand>) {
    /** Only expose integer sliders when the exact source custom shape is reported. */
    fun customGains(): List<Int>? = bands.takeIf { preset == 0 && masterGainHundredths == 0 &&
        it.map(QcyEqBand::frequency) == QcyWireCodec.customFrequencies &&
        it.all { band -> band.type == 2 && band.qHundredths == 100 &&
            band.gainHundredths in -800..800 && band.gainHundredths % 100 == 0 }
    }?.map { it.gainHundredths / 100 }
}

/** Missing values remain unknown. EQ v1 and v2 evidence are independent. */
@Serializable
@SerialName("qcy.settings")
data class QcySettingsFeatureState(
    val toggles: Map<QcyToggle, Boolean> = emptyMap(),
    val spatialMode: Int? = null,
    val eqPreset: Int? = null,
    val eqV1Preset: Int? = null,
    val eqV2Preset: Int? = null,
    val equalizer: QcyEqualizer? = null,
) : DeviceFeatureState {
    @Transient override val featureId = FEATURE_ID
    companion object { const val FEATURE_ID = "qcy.settings" }
}

@Serializable
sealed interface QcyControlRequest : ControlRequest {
    @Serializable @SerialName("qcy.set_toggle")
    data class SetToggle(val setting: QcyToggle, val enabled: Boolean) : QcyControlRequest
    @Serializable @SerialName("qcy.set_spatial_mode")
    data class SetSpatialMode(val mode: Int) : QcyControlRequest
    @Serializable @SerialName("qcy.set_eq_preset")
    data class SetEqPreset(val preset: Int) : QcyControlRequest
    @Serializable @SerialName("qcy.set_custom_eq")
    data class SetCustomEq(val gains: List<Int>) : QcyControlRequest
}

fun QcySettingsFeatureState.supports(request: QcyControlRequest): Boolean = when (request) {
    is QcyControlRequest.SetToggle -> toggles.containsKey(request.setting)
    is QcyControlRequest.SetSpatialMode -> spatialMode != null && request.mode in 0..2
    is QcyControlRequest.SetEqPreset -> (eqV1Preset != null || eqV2Preset != null) && request.preset in QcyWireCodec.presetIds
    is QcyControlRequest.SetCustomEq -> equalizer != null && request.gains.size == 10 && request.gains.all { it in -8..8 }
}
