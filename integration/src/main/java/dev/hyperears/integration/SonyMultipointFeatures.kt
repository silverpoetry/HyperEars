package dev.hyperears.integration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class SonyMultipointDefinition(val slot: Int, val title: String = "MULTIPOINT_SETTING")
@Serializable
data class SonyMultipointAlert(val transaction: Long, val message: Int, val target: Boolean, val expiresAtMillis: Long = Long.MAX_VALUE)
@Serializable @SerialName("sony.multipoint")
data class SonyMultipointFeatureState(
    val definition: SonyMultipointDefinition? = null,
    val enabled: Boolean? = null,
    val available: Boolean? = null,
    val alert: SonyMultipointAlert? = null,
    val pendingTransaction: Long? = null,
    val pendingExpiresAtMillis: Long? = null,
) : DeviceFeatureState {
    @Transient override val featureId = FEATURE_ID
    val hasSettings get() = definition != null && enabled != null
    fun supports(request: SonyControlRequest): Boolean = when (request) {
        is SonyControlRequest.SetMultipoint -> hasSettings && available == true && alert == null && pendingTransaction == null && request.expected == definition
        is SonyControlRequest.ReplyMultipointAlert -> hasSettings && alert != null && request.expected == definition && request.expectedAlert == alert
        else -> false
    }
    companion object { const val FEATURE_ID = "sony.multipoint" }
}
