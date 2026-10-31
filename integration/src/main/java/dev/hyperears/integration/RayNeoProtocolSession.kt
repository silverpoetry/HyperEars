package dev.hyperears.integration

import dev.hyperears.protocol.rayneo.RayNeoBleWireCodec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable @SerialName("rayneo.settings")
data class RayNeoSettingsFeatureState(
    val verifiedX3Pro: Boolean = false,
    val wifiEnabled: Boolean? = null,
    val deepSuspend: Boolean? = null,
    val autoLockSeconds: Int? = null,
    val brightness: Int? = null,
    val automaticBrightness: Boolean? = null,
    val mediaVolume: Int? = null,
    val systemVolume: Int? = null,
    val voiceMode: Int? = null,
    val supportMixShot: Boolean = false,
    val supportMixRecord: Boolean = false,
) : DeviceFeatureState {
    override val featureId: String get() = FEATURE_ID
    fun supports(request: ControlRequest): Boolean = verifiedX3Pro && when(request) {
        is RayNeoControlRequest.SetWifi -> wifiEnabled == request.expected &&
            (request.countryCode.isEmpty() || Regex("[A-Z]{2}").matches(request.countryCode))
        is RayNeoControlRequest.SetDeepSuspend -> deepSuspend == request.expected
        is RayNeoControlRequest.SetAutomaticBrightness -> automaticBrightness == request.expected
        is RayNeoControlRequest.SetVoiceMode -> voiceMode == request.expected && request.value in 0..2
        RayNeoControlRequest.CaptureShot -> supportMixShot
        is RayNeoControlRequest.SetRecording -> supportMixRecord
        is RayNeoControlRequest.SetLevel -> request.setting.value(this) == request.expected && request.value in 0..100 &&
            (request.setting != RayNeoLevelSetting.BRIGHTNESS || automaticBrightness == false)
        is RayNeoControlRequest.SetAutoLock -> deepSuspend == true && autoLockSeconds == request.expected &&
            request.seconds in RayNeoSettingsCodec.sleepTimes
        else -> false
    }
    companion object { const val FEATURE_ID = "rayneo.settings" }
}

@Serializable
@SerialName("rayneo.apps")
data class RayNeoAppsFeatureState(
    val installedPackages: Set<String>,
    val lastLaunchPackage: String? = null,
    val lastLaunchStatus: Int? = null,
    val lastLaunchSequence: Long = 0,
) : DeviceFeatureState {
    @Transient override val featureId = FEATURE_ID
    fun supports(request: RayNeoControlRequest.LaunchApp) = request.app.packageName in installedPackages
    fun supports(request: RayNeoControlRequest.LaunchInstalledApp) = request.packageName.length <= 255 && request.packageName in installedPackages
    companion object { const val FEATURE_ID = "rayneo.apps" }
}

/** Per-connection legacy application protocol. Android transport owns all I/O.
 * Readiness is established by a valid app-list reply, not merely an ATT write.
 * Explicit auth negotiation will be added separately if requested by the peer.
 */
class RayNeoProtocolSession(
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : ProtocolSession {
    private val decoder = RayNeoBleWireCodec.Decoder()
    private var state: RayNeoAppsFeatureState? = null
    private var settings = RayNeoSettingsFeatureState()
    private var wifi = RayNeoWifiFeatureState()
    private var capture = RayNeoCaptureFeatureState()
    private var pendingShot: Long? = null
    private var shotCommitted = false
    private var scanCountry: String? = null
    private var pendingStatus: Pair<ControlRequest, Long>? = null
    private val outgoing = mutableListOf<ByteArray>()
    private data class Pending(val packageName: String, val started: Long)
    private var pending: Pending? = null
    // No correlation ID in launch_status: after a timeout do not retry that app
    // within this connection and accidentally accept an old reply as new success.
    private val timedOutPackages = mutableSetOf<String>()

    override fun initialReadCommands() = listOf(RayNeoSettingsCodec.settingsQuery(), RayNeoSettingsCodec.statusQuery(),
        RayNeoLaunchCodec.installedAppsQuery()).flatMap(RayNeoLaunchCodec::fragment)
    override fun drainImmediateCommands(): List<ByteArray> = outgoing.toList().also { outgoing.clear() }
    private fun stopScan(): List<ByteArray> {
        val country = scanCountry ?: return emptyList()
        scanCountry = null
        return RayNeoLaunchCodec.fragment(RayNeoWifiCodec.scan(false,country))
    }
    override fun query(request: TelemetryQuery): List<ByteArray> =
        if (request is TelemetryQuery.RefreshFeature && request.featureId == RayNeoWifiFeatureState.STOP_QUERY) stopScan()
        else if (request is TelemetryQuery.RefreshFeature && request.featureId == RayNeoCaptureCodec.SHOT_COMMIT_QUERY) {
            val started = pendingShot
            if (started == null || shotCommitted || elapsedMs() - started !in 4_000L..10_000L) emptyList()
            else {
                shotCommitted = true
                RayNeoLaunchCodec.fragment(RayNeoCaptureCodec.commitShot())
            }
        } else super.query(request)
    override fun readback(request: ControlRequest): List<ByteArray> = when(request) {
        is RayNeoControlRequest.SetWifi -> RayNeoLaunchCodec.fragment(RayNeoSettingsCodec.statusQuery())
        is RayNeoControlRequest.SetAutoLock -> RayNeoLaunchCodec.fragment(RayNeoSettingsCodec.settingsQuery())
        else -> emptyList()
    }

    override fun encode(request: ControlRequest): List<ByteArray> {
        expirePending()
        if (request === StandardControlRequest.Refresh) return initialReadCommands()
        if (settings.verifiedX3Pro && settings.wifiEnabled == true) {
            when(request) {
                is RayNeoControlRequest.ScanWifi -> {
                    if(scanCountry != null || !(request.countryCode.isEmpty() || Regex("[A-Z]{2}").matches(request.countryCode))) return emptyList()
                    scanCountry = request.countryCode
                    return RayNeoLaunchCodec.fragment(RayNeoWifiCodec.scan(true,request.countryCode))
                }
                is RayNeoControlRequest.ConnectWifi -> {
                    if(!wifi.supports(request)) return emptyList()
                    return stopScan() + RayNeoLaunchCodec.fragment(RayNeoWifiCodec.connect(request))
                }
                else -> Unit
            }
        }
        if (settings.supports(request)) {
            val message = when(request) {
                is RayNeoControlRequest.SetWifi -> RayNeoSettingsCodec.wifi(request.enabled,request.countryCode)
                is RayNeoControlRequest.SetAutoLock -> RayNeoSettingsCodec.autoLock(request.seconds)
                RayNeoControlRequest.CaptureShot -> {
                    if (pendingShot?.let { elapsedMs() - it < 15_000 } == true) return emptyList()
                    pendingShot = elapsedMs()
                    shotCommitted = false
                    RayNeoCaptureCodec.command(true)
                }
                is RayNeoControlRequest.SetRecording -> {
                    if(request.start && capture.recording in setOf(RayNeoRecordingState.COUNTDOWN,RayNeoRecordingState.RECORDING)) return emptyList()
                    RayNeoCaptureCodec.command(false,request.start)
                }
                is RayNeoControlRequest.SetDeepSuspend, is RayNeoControlRequest.SetAutomaticBrightness,
                is RayNeoControlRequest.SetLevel, is RayNeoControlRequest.SetVoiceMode -> {
                    if(pendingStatus?.let { elapsedMs() - it.second < 5_000 } == true) return emptyList()
                    pendingStatus = request to elapsedMs()
                    RayNeoSettingsCodec.statusQuery()
                }
                else -> return emptyList()
            }
            return RayNeoLaunchCodec.fragment(message)
        }
        val packageName = when(request) {
            is RayNeoControlRequest.LaunchApp -> request.app.packageName.takeIf { state?.supports(request) == true }
            is RayNeoControlRequest.LaunchInstalledApp -> request.packageName.takeIf { state?.supports(request) == true }
            else -> null
        } ?: return emptyList()
        if (pending != null || packageName in timedOutPackages) return emptyList()
        val packets = RayNeoLaunchCodec.fragment(RayNeoLaunchCodec.message(packageName))
        pending = Pending(packageName, elapsedMs())
        return packets
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> {
        expirePending()
        val message = decoder.offer(bytes) ?: return emptyList()
        RayNeoCaptureCodec.parse(message)?.let { reply ->
            if (reply.shot) pendingShot = null
            capture = if(reply.shot) capture.copy(shotResult=reply.type) else capture.copy(
                recordResult=reply.type,
                recording=when(reply.type) {
                    1,2 -> RayNeoRecordingState.RECORDING
                    3 -> RayNeoRecordingState.COUNTDOWN
                    0 -> RayNeoRecordingState.STOPPED
                    else -> null
                })
            return listOf(ProtocolEvent.FeatureStateChanged(capture))
        }
        RayNeoWifiCodec.parse(message)?.let { reply ->
            val next = when(reply) {
                is RayNeoWifiReply.Scan -> {
                    outgoing += stopScan()
                    wifi.copy(networks = reply.networks)
                }
                is RayNeoWifiReply.Connection -> wifi.copy(ssid = reply.ssid, connectionState = reply.state)
            }
            val changed = next != wifi
            wifi = next
            return if(changed) listOf(ProtocolEvent.FeatureStateChanged(next)) else emptyList()
        }
        RayNeoSettingsCodec.parse(message)?.let { reply ->
            val previous = settings
            val events = mutableListOf<ProtocolEvent>()
            when(reply) {
                is RayNeoSettingsReply.Settings -> settings = settings.copy(verifiedX3Pro = reply.x3Pro,
                    autoLockSeconds = reply.autoLockSeconds,supportMixShot = reply.supportMixShot,supportMixRecord = reply.supportMixRecord)
                is RayNeoSettingsReply.Status -> {
                    settings = settings.copy(wifiEnabled = reply.wifiEnabled, deepSuspend = reply.deepSuspend,
                        brightness = reply.brightness,automaticBrightness = reply.automaticBrightness,
                        mediaVolume = reply.mediaVolume,systemVolume = reply.systemVolume,voiceMode=reply.voiceMode)
                    reply.battery?.let { reading ->
                        events.add(ProtocolEvent.CapabilitiesIdentified(battery = true, noiseModes = emptySet()))
                        events.add(ProtocolEvent.FeatureStateChanged(BatteryFeatureState(EarbudBattery(overall = reading))))
                    }
                    pendingStatus?.let { (request, started) ->
                        if (elapsedMs() - started < 5_000 && settings.supports(request)) {
                            val update = when(request) {
                                is RayNeoControlRequest.SetDeepSuspend -> RayNeoSettingsCodec.deepSuspend(reply.raw,request.enabled)
                                is RayNeoControlRequest.SetAutomaticBrightness -> RayNeoSettingsCodec.automaticBrightness(reply.raw,request.enabled)
                                is RayNeoControlRequest.SetLevel -> RayNeoSettingsCodec.level(reply.raw,request.setting,request.value)
                                is RayNeoControlRequest.SetVoiceMode -> RayNeoSettingsCodec.voiceMode(reply.raw,request.value)
                                else -> null
                            }
                            if(update != null) outgoing += RayNeoLaunchCodec.fragment(update)
                            outgoing += RayNeoLaunchCodec.fragment(RayNeoSettingsCodec.statusQuery())
                        }
                    }
                    pendingStatus = null
                }
            }
            if(settings != previous) events.add(ProtocolEvent.FeatureStateChanged(settings))
            return events
        }
        return when (val reply = RayNeoLaunchCodec.parse(message)) {
            is RayNeoLaunchReply.InstalledApps -> {
                val first = state == null
                val next = (state ?: RayNeoAppsFeatureState(emptySet())).copy(installedPackages = reply.packages)
                val changed = next != state
                state = next
                buildList {
                    if (first) add(ProtocolEvent.HandshakeAccepted)
                    if (changed) add(ProtocolEvent.FeatureStateChanged(next))
                }
            }
            is RayNeoLaunchReply.LaunchStatus -> {
                val active = pending ?: return emptyList()
                if (reply.packageName != active.packageName) return emptyList()
                pending = null
                val next = state?.copy(lastLaunchPackage = reply.packageName, lastLaunchStatus = reply.status,
                    lastLaunchSequence = (state?.lastLaunchSequence ?: 0) + 1)
                    ?: return emptyList()
                state = next
                listOf(ProtocolEvent.FeatureStateChanged(next))
            }
            null -> emptyList()
        }
    }

    private fun expirePending() {
        pending?.takeIf { elapsedMs() - it.started >= 5_000 }?.let {
            timedOutPackages.add(it.packageName)
            pending = null
        }
    }

    override fun reset() {
        decoder.reset()
        state = null
        settings = RayNeoSettingsFeatureState()
        wifi = RayNeoWifiFeatureState()
        capture = RayNeoCaptureFeatureState()
        pendingShot = null
        shotCommitted = false
        scanCountry = null
        pendingStatus = null
        outgoing.clear()
        pending = null
        timedOutPackages.clear()
    }
}
