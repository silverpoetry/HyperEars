package dev.hyperears.integration

import dev.hyperears.protocol.sony.SonyDailyControlsCodec as Wire

/** Session-only capability evidence. No model names, Android dependencies or timers. */
internal class SonyDailyControlsSession(private val v2: Boolean) {
    private val assignable = SonyAssignableSession(v2)
    private val multipoint = SonyMultipointSession(v2)
    fun multipointState() = multipoint.state
    private val audio = SonyAudioSettingsSession(v2)
    fun audioState() = audio.state
    private var functionsKnown = false
    private var functions = Wire.Functions(emptySet(), emptySet())
    private val geometry = mutableMapOf<Int, List<Wire.Band>>()
    private val geometryAwaitingParameters = mutableSetOf<Int>()
    private val reports = mutableMapOf<Int, Wire.Eq>()
    private val statuses = mutableMapOf<Int, Boolean>()
    private val ambientReports = mutableMapOf<Int, Wire.Ambient>()
    private val positiveAmbientLevels = mutableMapOf<Int, Int>()
    private var ambientReport: Wire.Ambient? = null
    private val ebbType get() = if (v2) 1 else 2
    data class Update(val states: List<DeviceFeatureState>, val queries: List<ByteArray>)
    fun accept(payload: ByteArray): Update? {
        val assignableUpdate = assignable.accept(payload)
        val multipointUpdate = multipoint.accept(payload)
        val audioUpdate = audio.accept(payload)
        Wire.functions(v2, payload)?.let { next ->
            if (functionsKnown && next == functions) return if (audioUpdate != null || multipointUpdate != null || assignableUpdate != null) Update(audioUpdate?.states.orEmpty() + multipointUpdate?.states.orEmpty() + assignableUpdate?.states.orEmpty(), audio.reads() + multipoint.reads() + assignable.reads(true)) else Update(emptyList(), emptyList())
            functionsKnown = true
            functions = next
            geometry.keys.retainAll(next.eqTypes); reports.keys.retainAll(next.eqTypes)
            geometryAwaitingParameters.retainAll(next.eqTypes)
            statuses.keys.retainAll(next.eqTypes); ambientReports.keys.retainAll(next.ambientTypes)
            positiveAmbientLevels.keys.retainAll(next.ambientTypes)
            ambientReport = chooseAmbient()
            return Update(listOf(eqState(), ambientState() ?: SonyAmbientFeatureState(0, false, false, true)) + audioUpdate?.states.orEmpty() + multipointUpdate?.states.orEmpty() + assignableUpdate?.states.orEmpty(), reads(includeCapabilities = true))
        }
        if (assignableUpdate != null) return assignableUpdate
        if (multipointUpdate != null) return multipointUpdate
        if (audioUpdate != null) return audioUpdate
        var changed = false
        val queries = mutableListOf<ByteArray>()
        Wire.geometry(payload)?.let { (type, bands) -> if (type in functions.eqTypes) {
            val previous = geometry.put(type, bands)
            if (previous != null && previous != bands) {
                reports.remove(type)
                geometryAwaitingParameters += type
                queries += Wire.bytes(0x56, type)
            }
            changed = true
        } }
        Wire.status(payload)?.let { (type, enabled) -> if (type in functions.eqTypes) { statuses[type] = enabled; changed = true } }
        Wire.equalizer(v2, payload)?.let { report ->
            val bands = geometry[report.type]
            val ten = bands?.size == 10 && bands.none { it.kind == 0x10 }
            if (report.type in functions.eqTypes && (!ten || report.steps.all { it in 0..12 })) {
                reports[report.type] = report
                geometryAwaitingParameters.remove(report.type)
                changed = true
            }
        }
        if (changed) return Update(listOf(eqState()), queries)
        Wire.ambient(v2, payload)?.let { report ->
            if (report.type !in functions.ambientTypes) return null
            ambientReports[report.type] = report
            if (report.level > 0) positiveAmbientLevels[report.type] = report.level
            ambientReport = chooseAmbient()
            return Update(listOfNotNull(ambientState()), emptyList())
        }
        return null
    }
    private fun chooseAmbient(): Wire.Ambient? = listOf(0x17, 0x19, 0x14).firstNotNullOfOrNull(ambientReports::get)
        ?: ambientReports.values.firstOrNull()
    fun eqState(): SonyEqFeatureState {
        val preferred = (if (v2) listOf(0, 3, 4, 0x31, 2) else listOf(1, 3)).firstOrNull { it in reports || it in geometryAwaitingParameters }
        val eq = reports[preferred]
        val bands = geometry[preferred].orEmpty().map { SonyEqBand(it.kind, it.value) }
        val ebb = reports[ebbType]
        return SonyEqFeatureState(if (v2) 2 else 1, preferred, statuses[preferred], eq?.preset, eq?.ult,
            eq?.steps.orEmpty(), bands, ebb?.bass ?: ebb?.steps?.firstOrNull()?.takeIf { it in 0..20 }?.minus(10), statuses[ebbType])
    }
    fun ambientState(): SonyAmbientFeatureState? = ambientReport?.let { SonyAmbientFeatureState(it.level, it.voice, it.voiceWritable, it.adaptive) }
    fun reads(includeCapabilities: Boolean = false): List<ByteArray> = buildList {
        addAll(assignable.reads(includeCapabilities))
        addAll(audio.reads())
        addAll(multipoint.reads())
        functions.eqTypes.forEach { type ->
            if (includeCapabilities) add(Wire.bytes(0x50, type, 0))
            add(Wire.bytes(0x52, type)); add(Wire.bytes(0x56, type))
            if (type != ebbType) add(Wire.bytes(0x5A, type))
        }
        functions.ambientTypes.forEach { type ->
            if (includeCapabilities) add(Wire.bytes(0x60, type))
            add(Wire.bytes(0x66, type))
        }
    }
    /** Preserve reported level/voice/adaptive bytes when using the existing native noise control. */
    fun noiseWrite(mode: NoiseMode): List<ByteArray>? {
        val report = ambientReport ?: return null
        // SonyPods Repository.setNoiseControlMode uses 10 when no positive level was reported.
        // This is an intentional transparency-write target, never published as observed state.
        val level = if (mode == NoiseMode.TRANSPARENCY) positiveAmbientLevels[report.type] ?: 10 else report.level
        val p = report.payload.toMutableList()
        p[0] = 0x68
        if (!v2) {
            p[2] = if (mode == NoiseMode.OFF) 0 else 0x11
            p[4] = when (mode) { NoiseMode.ANC -> if (p[3] == 2) 2 else 1; NoiseMode.WIND -> 1; else -> 0 }
            p[7] = if (mode == NoiseMode.TRANSPARENCY) level else 0
        } else {
            p[2] = 1; p[3] = if (mode == NoiseMode.OFF) 0 else 1
            p[when (report.type) { 0x15, 0x16 -> 7; 0x22 -> 5; else -> 6 }] = level
            when (report.type) {
                0x13 -> p[4] = if (mode == NoiseMode.ANC) 1 else 0
                0x14 -> p[4] = when (mode) { NoiseMode.ANC -> 2; NoiseMode.WIND -> 1; else -> 0 }
                0x15, 0x16 -> {
                    p[4] = if (mode == NoiseMode.TRANSPARENCY) 1 else 0
                    p[5] = if (mode == NoiseMode.WIND) { if (report.type == 0x15) 3 else 1 } else 2
                }
                0x17, 0x18, 0x19 -> p[4] = if (mode == NoiseMode.TRANSPARENCY) 1 else 0
                0x22 -> if (mode !in setOf(NoiseMode.TRANSPARENCY, NoiseMode.OFF)) return emptyList()
                else -> return emptyList()
            }
        }
        return listOf(p.map(Int::toByte).toByteArray(), Wire.bytes(0x66, report.type))
    }
    /** Return write followed by readbacks, which the owner places on its existing ACK queue. */
    fun encode(request: SonyControlRequest): List<ByteArray> {
        if (request is SonyControlRequest.SetAssignablePreset) return assignable.encode(request)
        if (request is SonyControlRequest.SetMultipoint || request is SonyControlRequest.ReplyMultipointAlert) return multipoint.encode(request)
        if (request is SonyControlRequest.SetDsee || request is SonyControlRequest.SetConnectionQuality || request is SonyControlRequest.SetSpeakToChat)
            return audio.encode(request)
        val state = eqState()
        if (request is SonyControlRequest.SetAmbient) {
            val ambient = ambientReport ?: return emptyList()
            if (ambientState()?.supports(request) != true) return emptyList()
            return listOf(Wire.setAmbient(v2, ambient, request.level, request.voice), Wire.bytes(0x66, ambient.type))
        }
        if (!state.supports(request)) return emptyList()
        val type = state.parameterType
        val write = when (request) {
            is SonyControlRequest.SetEqPreset -> Wire.setEq(v2, requireNotNull(type), request.preset, emptyList(), state.ultMode)
            is SonyControlRequest.SetEqBands -> bandWrite(state, request.steps.map { it + state.center })
            is SonyControlRequest.SetClearBass -> if (state.clearBassSlot) {
                bandWrite(state, state.rawSteps.toMutableList().apply { this[0] = request.level + state.center })
            } else Wire.bytes(0x58, ebbType, request.level)
            else -> return emptyList()
        }
        val readType = if (request is SonyControlRequest.SetClearBass && !state.clearBassSlot) ebbType else requireNotNull(type)
        return listOf(write, Wire.bytes(0x56, readType), Wire.bytes(0x52, readType))
    }
    private fun bandWrite(state: SonyEqFeatureState, raw: List<Int>): ByteArray {
        val preset = if (!v2) 255 else state.preset?.takeIf { it in 0xA0..0xA2 } ?: 0xA0
        return Wire.setEq(v2, requireNotNull(state.parameterType), preset, raw, state.ultMode)
    }
}
