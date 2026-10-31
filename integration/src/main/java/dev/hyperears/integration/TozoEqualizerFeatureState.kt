package dev.hyperears.integration

import dev.hyperears.protocol.tozo.TozoEqualizerCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class TozoEqBand(val frequencyHz: Int, val gainTenths: Int, val qValue: Int) {
    internal fun wire() = TozoEqualizerCodec.Band(frequencyHz, gainTenths, qValue)
}

@Serializable @SerialName("tozo.nc20pro.equalizer")
data class TozoEqualizerFeatureState(val bands: List<TozoEqBand>) : DeviceFeatureState {
    override val featureId: String get() = ID
    val valid: Boolean get() = bands.size == 10 && bands.all {
        it.frequencyHz in 0..65535 && it.gainTenths in -128..127 && it.qValue in 0..255
    }
    val writableGains: Boolean get() = valid && bands.all { it.gainTenths in -50..50 }
    fun supports(request: TozoControlRequest.SetEqualizer) = writableGains && request.expected == this &&
        request.gainsTenths.size == bands.size && request.gainsTenths.all { it in -50..50 } &&
        request.gainsTenths != bands.map { it.gainTenths }
    companion object { const val ID = "tozo.nc20pro.equalizer" }
}
