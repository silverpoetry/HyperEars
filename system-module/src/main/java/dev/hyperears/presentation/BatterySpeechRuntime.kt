package dev.hyperears.presentation

import dev.hyperears.integration.*
import dev.hyperears.settings.*
import java.util.Locale

internal enum class SpeechBatteryPart { LEFT, RIGHT, HEADSET }
internal data class SpeechBatteryReading(val part: SpeechBatteryPart, val percent: Int, val charging: Boolean)
internal data class BatterySpeechMessage(val connection: Boolean, val low: List<SpeechBatteryPart>) {
    fun text(): String = buildList {
        if (connection) add("耳机已连接")
        if (low.isNotEmpty()) add(low.joinToString("、") { when(it) {
            SpeechBatteryPart.LEFT -> "左耳"; SpeechBatteryPart.RIGHT -> "右耳"; SpeechBatteryPart.HEADSET -> "耳机"
        } } + "电量低")
    }.joinToString("。")
}
internal object SpeechBatteryProjection {
    fun readings(state: EarbudState): List<SpeechBatteryReading> {
        val battery = state.battery
        val useOverall = battery.overall.available && (state.adapter?.batterySource != BatterySource.PRIVATE_PROTOCOL ||
            (!battery.left.available && !battery.right.available))
        val values = if (useOverall) listOf(SpeechBatteryPart.HEADSET to battery.overall)
            else listOf(SpeechBatteryPart.LEFT to battery.left, SpeechBatteryPart.RIGHT to battery.right)
        return values.mapNotNull { (part, value) -> value.percent?.let { SpeechBatteryReading(part, it, value.charging) } }
    }
}
internal interface BatterySpeechHost {
    fun now(): Long
    fun allowed(address: String, mode: BatterySpeechMode): Boolean
    fun speak(address: String, mode: BatterySpeechMode, volume: Float, message: BatterySpeechMessage,
              valid: () -> Boolean, finished: () -> Unit): Boolean
    fun stop()
}

/** Report-driven opportunity consumption is independent of TTS success and never queues speech. */
internal class BatterySpeechRuntime(
    private val host: BatterySpeechHost,
    private val producerAlive: (Any) -> Boolean = { true },
    private val settings: () -> ModuleSettings,
) {
    private data class History(val connection: HeadsetConnection, var announced: Boolean = false,
        var lowConsumed: Boolean = false, var battery: List<SpeechBatteryReading> = emptyList())
    private data class Record(val state: EarbudState, val session: String, val metadata: NativePresentationMetadata, val producer: Any)
    private data class Job(val address: String, val session: String, val connection: HeadsetConnection,
        val producer: Any, val mode: BatterySpeechMode, val volume: BatterySpeechVolume,
        val message: BatterySpeechMessage, val config: Pair<Boolean,Boolean>)
    private val history = linkedMapOf<String, History>()
    private val current = mutableMapOf<String, Record>()
    private var active: Job? = null

    fun update(state: EarbudState, session: String, metadata: NativePresentationMetadata, producer: Any) {
        val address = state.address?.uppercase(Locale.ROOT)?.takeIf { MAC.matches(it) } ?: return
        val old = history[address]
        if (old != null && metadata.connection.connectedAtElapsedMs < old.connection.connectedAtElapsedMs) return
        if (old == null && history.size >= 128) return
        val entry = if (old?.connection == metadata.connection) old else History(metadata.connection).also { history[address] = it }
        val record = Record(state, session, metadata, producer)
        current[address] = record
        val config = settings()
        val readings = SpeechBatteryProjection.readings(state)
        val changed = entry.battery != readings
        entry.battery = readings
        val low = readings.filter { it.percent in 0..19 && !it.charging }.map { it.part }
        if (active != null && !valid(requireNotNull(active))) cancel()
        if (metadata.replay) {
            entry.announced = true
            if (low.isNotEmpty()) entry.lowConsumed = true
            return
        }
        val eligible = eligible(record, config)
        val fresh = host.now() - metadata.connection.connectedAtElapsedMs in 0 until 10_000L
        val connection = !entry.announced && fresh && eligible && config.speechOnConnection
        if (!fresh || config.speechMode == BatterySpeechMode.OFF || !config.speechOnConnection || eligible) entry.announced = true
        val lowEvent = !entry.lowConsumed && changed && low.isNotEmpty()
        if (lowEvent) entry.lowConsumed = true
        val spokenLow = low.takeIf { lowEvent && config.speechLowBattery && eligible }.orEmpty()
        if ((!connection && spokenLow.isEmpty()) || active != null || !eligible) return
        val job = Job(address, session, metadata.connection, producer, config.speechMode, config.speechVolume,
            BatterySpeechMessage(connection, spokenLow), config.speechOnConnection to config.speechLowBattery)
        if (!runCatching { host.allowed(address, job.mode) }.getOrDefault(false)) return
        active = job
        val accepted = runCatching { host.speak(address, job.mode, job.volume.percent / 100f, job.message,
            { active === job && valid(job) }, { if (active === job) active = null }) }.getOrDefault(false)
        if (!accepted && active === job) cancel()
    }
    fun settingsChanged() {
        cancel()
        current.forEach { (address, record) -> history[address]?.let { entry ->
            entry.announced = true
            entry.battery = SpeechBatteryProjection.readings(record.state)
            if (entry.battery.any { it.percent in 0..19 && !it.charging }) entry.lowConsumed = true
        } }
    }
    fun remove(address: String) {
        val key = address.uppercase(Locale.ROOT)
        current.remove(key)
        history[key]?.let { it.announced = true; it.lowConsumed = true }
        if (active?.address == key) cancel()
    }
    fun clear() { cancel(); current.clear(); history.values.forEach { it.announced = true; it.lowConsumed = true } }
    private fun cancel() { active = null; runCatching { host.stop() } }
    private fun eligible(record: Record, config: ModuleSettings): Boolean = config.speechMode != BatterySpeechMode.OFF &&
        !config.modulePaused && producerAlive(record.producer) && record.metadata.admission == NativeAdmission.HYPEREARS &&
        record.state.sessionActive && record.state.lifecycle.systemProfile == SystemProfileState.CONNECTED &&
        record.state.lifecycle.controlOwnership == ControlOwnership.MODULE && record.state.adapter?.id !in config.disabledAdapterIds &&
        record.state.features.get<AppleConversationFeatureState>()?.supplementaryActivity != AppleConversationActivity.START
    private fun valid(job: Job): Boolean {
        val record = current[job.address] ?: return false
        val config = settings()
        val currentLow = SpeechBatteryProjection.readings(record.state)
            .filter { it.percent in 0..19 && !it.charging }.map { it.part }
        if (!currentLow.containsAll(job.message.low)) return false
        return record.producer === job.producer && record.session == job.session && record.metadata.connection == job.connection &&
            config.speechMode == job.mode && config.speechVolume == job.volume &&
            (config.speechOnConnection to config.speechLowBattery) == job.config && eligible(record, config)
    }
    companion object { private val MAC = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}") }
}
