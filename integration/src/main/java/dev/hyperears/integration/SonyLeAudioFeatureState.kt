package dev.hyperears.integration

import dev.hyperears.protocol.sony.SonyLeAudioCodec as Wire
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class SonyLeAudioIdentity(val classic: String, val le: List<String>)

@Serializable
data class SonyLeAudioAlert(
    val domain: Int, val message: Int, val action: Int, val items: List<Int> = emptyList(),
)

/** Session evidence only. Availability does not imply Android profile readiness. */
@Serializable
@SerialName("sony.le_audio")
data class SonyLeAudioFeatureState(
    val supported: Boolean = false,
    val alert: SonyLeAudioAlert? = null,
    val alertSequence: Long = 0,
    val alertExpiresAtMillis: Long = 0,
    val classicAddress: String? = null,
    val leAddresses: List<String> = emptyList(),
    val available: Boolean? = null,
    val enabled: Boolean? = null,
    val pairedHistory: Int? = null,
    val historySequence: Long = 0,
    val notificationSequence: Long = 0,
    val notifiedEnabled: Boolean? = null,
) : DeviceFeatureState {
    @Transient override val featureId = FEATURE_ID

    val identity: SonyLeAudioIdentity? get() = if (supported && classicAddress != null && leAddresses.isNotEmpty())
        SonyLeAudioIdentity(classicAddress,leAddresses) else null

    fun supports(request: SonyControlRequest.SetLeAudio) = available == true && alert == null && identity != null &&
        identity == request.expectedIdentity && enabled == request.expectedEnabled && enabled != request.enabled
    fun supports(request: SonyControlRequest.ReadLeAudioHistory) = identity != null && identity == request.expectedIdentity

    internal fun acceptAlert(value: dev.hyperears.protocol.sony.SonyLeAudioAlertCodec.Alert): SonyLeAudioFeatureState =
        if (!supported) this else copy(alert = SonyLeAudioAlert(value.domain,value.message,value.action,value.items),
            alertSequence = alertSequence + 1, alertExpiresAtMillis = System.nanoTime() / 1_000_000 + 30_000)

    fun supports(request: SonyControlRequest.ReplyLeAudioAlert, now: Long = System.nanoTime() / 1_000_000): Boolean =
        supported && alert != null && now < alertExpiresAtMillis && request.expectedSequence == alertSequence &&
            request.expectedAlert == alert && replyBytes(request) != null

    internal fun replyBytes(request: SonyControlRequest.ReplyLeAudioAlert): ByteArray? =
        dev.hyperears.protocol.sony.SonyLeAudioAlertCodec.reply(
            dev.hyperears.protocol.sony.SonyLeAudioAlertCodec.Alert(request.expectedAlert.domain,
                request.expectedAlert.message,request.expectedAlert.action,request.expectedAlert.items),request.confirm)

    internal fun accept(report: Wire.Report): SonyLeAudioFeatureState = when {
        report is Wire.Report.Capability -> if (report.supported) copy(supported = true) else SonyLeAudioFeatureState(alertSequence = alertSequence)
        !supported -> this
        else -> when (report) {
            is Wire.Report.Identity -> copy(classicAddress = report.classic, leAddresses = report.leAddresses)
            is Wire.Report.Availability -> copy(available = report.available)
            is Wire.Report.History -> copy(pairedHistory = report.value, historySequence = historySequence + 1)
            is Wire.Report.Setting -> copy(enabled = report.enabled,
                notificationSequence = notificationSequence + if (report.notification) 1 else 0,
                notifiedEnabled = if (report.notification) report.enabled else notifiedEnabled)
            is Wire.Report.Capability -> this
        }
    }

    companion object { const val FEATURE_ID = "sony.le_audio" }
}
