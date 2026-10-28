package dev.hyperears.integration

import dev.hyperears.protocol.moondrop.PuddingApkWireCodec as Wire

/** Pudding-only negotiation. The captured dialogue remains an untouched delegate. */
internal class PuddingAdvancedSession : ProtocolSession {
    private enum class Dialect { LEGACY, APK }
    private var dialect: Dialect? = null
    private val legacy = MoondropPuddingProtocolSession()
    private val apk = Wire.Decoder()
    val isApk: Boolean get() = dialect == Dialect.APK
    private var fallbackSent = false
    private var settings = PuddingSettingsFeatureState()
    private var battery = EarbudBattery()
    override fun initialReadCommands() = legacy.initialReadCommands()
    private fun reads() = PuddingSetting.entries.map { Wire.query(it.query) } + Wire.batteryQuery
    override fun followUpCommands(event: ProtocolEvent) = if (dialect == Dialect.LEGACY) legacy.followUpCommands(event)
        else if (dialect == Dialect.APK && event === ProtocolEvent.HandshakeAccepted) reads() else emptyList()
    override fun query(request: TelemetryQuery): List<ByteArray> {
        if (request is TelemetryQuery.RefreshFeature && request.featureId == PuddingSettingsFeatureState.DIALECT_QUERY) {
            if (dialect != null || fallbackSent) return emptyList()
            fallbackSent = true
            return listOf(Wire.handshake) + reads()
        }
        return when (dialect) {
            Dialect.LEGACY -> legacy.query(request)
            Dialect.APK -> reads()
            null -> emptyList()
        }
    }
    override fun encode(request: ControlRequest): List<ByteArray> = when (dialect) {
        Dialect.LEGACY -> if (request is PuddingControlRequest) emptyList() else legacy.encode(request)
        Dialect.APK -> when (request) {
            is PuddingControlRequest.SetValue -> if (settings.supports(request)) listOf(Wire.write(request.setting.query,request.value)) else emptyList()
            is StandardControlRequest.SetNoiseMode -> if (settings.values.containsKey(PuddingSetting.NOISE)) listOf(Wire.write(64,when(request.mode) {
                NoiseMode.OFF -> 0; NoiseMode.ANC -> 1; NoiseMode.TRANSPARENCY -> 2; NoiseMode.WIND -> 3
            })) else emptyList()
            StandardControlRequest.Refresh -> reads()
            else -> emptyList()
        }
        null -> emptyList()
    }
    override fun readback(request: ControlRequest) = if (dialect == Dialect.LEGACY) legacy.readback(request) else emptyList()
    override fun offer(bytes: ByteArray): List<ProtocolEvent> {
        if (dialect == Dialect.LEGACY) return legacy.offer(bytes)
        val apkPayloads = apk.offer(bytes)
        val legacyEvents = if (dialect == null) legacy.offer(bytes) else emptyList()
        if (legacyEvents.any { it === ProtocolEvent.HandshakeAccepted }) {
            if (apkPayloads.any { Wire.scalar(it) != null }) { legacy.reset(); apk.reset(); return emptyList() }
            dialect = Dialect.LEGACY; apk.reset(); return legacyEvents
        }
        return buildList {
            apkPayloads.forEach { payload ->
                val scalar = Wire.scalar(payload)
                val batteryReport = Wire.battery(payload)
                if (scalar == null && (dialect == null || batteryReport == null)) return@forEach
                // A legacy six-parameter battery frame has a valid APK single-pair prefix.
                // Battery and negotiation therefore never select the transport dialect.
                if (dialect == null) { dialect = Dialect.APK; legacy.reset(); add(ProtocolEvent.HandshakeAccepted) }
                scalar?.let {
                    val key = when(it.sub) { 65 -> PuddingSetting.NOISE; 31 -> PuddingSetting.GAIN; else -> PuddingSetting.LED }
                    settings = settings.copy(values = settings.values + (key to it.value))
                    add(ProtocolEvent.FeatureStateChanged(settings))
                    if (key == PuddingSetting.NOISE) {
                        add(ProtocolEvent.CapabilitiesIdentified(battery=false,noiseModes=NoiseMode.entries.toSet()))
                        add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(when(it.value) {
                            0 -> NoiseMode.OFF; 2 -> NoiseMode.TRANSPARENCY; 3 -> NoiseMode.WIND; else -> NoiseMode.ANC
                        })))
                    }
                }
                batteryReport?.let {
                    battery = battery.copy(left=it[1]?.let { BatteryReading(it,false) } ?: battery.left,
                        right=it[2]?.let { BatteryReading(it,false) } ?: battery.right,
                        case=it[3]?.let { BatteryReading(it,false) } ?: battery.case)
                    add(ProtocolEvent.CapabilitiesIdentified(battery=true))
                    add(ProtocolEvent.FeatureStateChanged(BatteryFeatureState(battery)))
                }
            }
        }
    }
    override fun reset() { dialect=null; legacy.reset(); apk.reset(); fallbackSent=false; settings=PuddingSettingsFeatureState(); battery=EarbudBattery() }
}
