package dev.hyperears.integration

import dev.hyperears.protocol.sony.SonyInformationCodec as Wire
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Transient

@Serializable @SerialName("sony.information")
data class SonyInformationFeatureState(
    val modelName: String? = null, val firmware: String? = null, val displayFirmware: String? = null,
    val series: Int? = null, val color: Int? = null,
    val codec: Int? = null, val upscalingGeneration: Int? = null, val upscalingStatus: Int? = null,
) : DeviceFeatureState {
    @Transient override val featureId = FEATURE_ID
    fun accept(report: Wire.Report): SonyInformationFeatureState = when (report) {
        is Wire.Report.Text -> when (report.kind) { 1 -> copy(modelName = report.value); 2 -> copy(firmware = report.value); 9 -> copy(displayFirmware = report.value); else -> this }
        is Wire.Report.Color -> copy(series = report.series, color = report.color)
        is Wire.Report.Codec -> copy(codec = report.value)
        is Wire.Report.Upscaling -> copy(upscalingGeneration = report.generation, upscalingStatus = report.status)
    }
    fun artwork(): HeadsetArtwork? {
        if (modelName != "WH-1000XM6") return null
        val code = color?.let { if (it in 0x10..0x1F) it and 15 else it } ?: return null
        val suffix = when (code) { 0, 1 -> "01"; 3 -> "03"; 5 -> "05"; 6 -> "06"; 8 -> "08"; 13 -> "0d"; else -> return null }
        return HeadsetArtwork(HeadsetArtworkId("headset_sony_wh1000xm6_$suffix"))
    }
    companion object { const val FEATURE_ID = "sony.information" }
}
