package dev.hyperears.integration

import dev.hyperears.protocol.samsung.SamsungBudsCodec
import dev.hyperears.protocol.samsung.SamsungDailySettingsCodec as Wire

internal class SamsungDailySettingsSession(private val model: SamsungBudsCodec.Model) {
    var state: SamsungDailySettingsFeatureState? = null
        private set
    private var pendingFitStop=false
    fun reset() { state = null;pendingFitStop=false }
    fun expireFit(lease:Long):List<ByteArray> = if(state?.fitLease==lease)releaseFit()else emptyList()
    fun releaseFit():List<ByteArray> {
        val current=state
        val owned=current?.fitLease!=null || pendingFitStop
        pendingFitStop=false
        state=current?.copy(fitLease=null)
        return if(owned)listOf(dev.hyperears.protocol.samsung.SamsungFitTestCodec.command(model,false))else emptyList()
    }
    fun drainCommands():List<ByteArray> {if(!pendingFitStop)return emptyList();pendingFitStop=false;return listOf(dev.hyperears.protocol.samsung.SamsungFitTestCodec.command(model,false))}
    fun encode(request: SamsungDailyControlRequest): List<ByteArray> {
        val current = state ?: return emptyList()
        if (!current.supports(request)) return emptyList()
        fun packet(id: Int, vararg payload: Int) = listOf(SamsungBudsCodec.packet(id, payload.map(Int::toByte).toByteArray(), model))
        if (request is SamsungDailyControlRequest.Find && request.start) state = current.copy(findStopped = null)
        return when (request) {
            is SamsungDailyControlRequest.FitTest -> {
                state=if(request.start)current.copy(fitLease=request.lease,fitResult=null,fitResultLease=null)else current.copy(fitLease=null)
                listOf(dev.hyperears.protocol.samsung.SamsungFitTestCodec.command(model,request.start))
            }
            is SamsungDailyControlRequest.SetConversationTimeout -> packet(Wire.CONVERSATION_TIMEOUT,request.seconds/5-1)
            is SamsungDailyControlRequest.SetEqualizer -> listOf(Wire.equalizer(model, request.enabled, request.preset))
            is SamsungDailyControlRequest.SetAmbientVolume -> packet(0x84, request.level)
            is SamsungDailyControlRequest.SetAncLevel -> packet(Wire.ANC_LEVEL, request.level)
            is SamsungDailyControlRequest.SetCustomAmbient -> listOf(Wire.customAmbient(model, request.enabled, request.left, request.right, request.tone))
            is SamsungDailyControlRequest.SetCustomEqualizer -> Wire.customEqualizer(model, request.gains)
            is SamsungDailyControlRequest.SetLegacyLock -> packet(0x90, if (request.locked) 1 else 0)
            is SamsungDailyControlRequest.SetLegacyHold -> packet(0x92, request.left, request.right)
            is SamsungDailyControlRequest.SetVoiceFocus -> packet(0x85, if (request.enabled) 1 else 0)
            is SamsungDailyControlRequest.Find -> packet(if (request.start) Wire.FIND_START else Wire.FIND_STOP)
            is SamsungDailyControlRequest.MuteFind -> packet(Wire.FIND_MUTE, if (request.left) 1 else 0, if (request.right) 1 else 0)
        }
    }
    fun offer(frame: SamsungBudsCodec.Frame): SamsungDailySettingsFeatureState? {
        dev.hyperears.protocol.samsung.SamsungFitTestCodec.result(frame)?.let { result ->
            val current=state?:return null
            val lease=current.fitLease?:return null
            if(!dev.hyperears.protocol.samsung.SamsungFitTestCodec.supported(model))return null
            pendingFitStop=true
            state=current.copy(fitLease=null,fitResult=result,fitResultLease=lease)
            return state
        }
        Wire.parse(frame, model)?.let { report ->
            // Fresh complete common report replaces capabilities; absent/invalid fields revoke only those controls.
            val old = state
            state = SamsungDailySettingsFeatureState(model.name, report.revision, report.eqEnabled, report.eqPreset,
                report.ambient, report.ambientMaximum, report.ancLevel,
                report.customAmbient?.let { SamsungCustomAmbient(it.enabled, it.left, it.right, it.tone, it.maximum) },
                report.locked, report.leftHold, report.rightHold, report.voiceFocus,
                Wire.customEqSupported(model) && report.eqEnabled != null && report.eqPreset != null,
                old?.findLeftMuted, old?.findRightMuted, old?.findStopped, report.conversationTimeoutSeconds,old?.fitLease,old?.fitResult,old?.fitResultLease)
            return state
        }
        val current = state ?: return null
        val ack = SamsungBudsCodec.parseAcknowledgement(frame)
        val id = ack?.commandId ?: frame.id
        val p = ack?.parameters ?: frame.payload
        fun single(range: IntRange) = p.singleOrNull()?.toInt()?.and(255)?.takeIf { it in range }
        fun bool() = single(0..1)?.let { it == 1 }
        val next = when (id) {
            Wire.CONVERSATION_TIMEOUT -> if (ack == null || model != SamsungBudsCodec.Model.BUDS2_PRO || current.conversationTimeoutSeconds == null) null
                else single(0..2)?.let { current.copy(conversationTimeoutSeconds=(it+1)*5) }
            0x86 -> if (ack == null || current.equalizerEnabled == null) null else {
                if (model.original) p.takeIf { it.size == 2 && it[0].toInt() in 0..1 && it[1].toInt() in 5..9 }
                    ?.let { current.copy(equalizerEnabled = it[0].toInt() == 1, equalizerPreset = it[1].toInt() - 5) }
                else single(0..if (Wire.customEqSupported(model)) 7 else 5)?.takeIf { it != 6 }
                    ?.let { current.copy(equalizerEnabled = it != 0, equalizerPreset = (it - 1).coerceAtLeast(0)) }
            }
            0x84 -> single(0..current.ambientMaximum)?.takeIf { current.ambientVolume != null }
                ?.let { current.copy(ambientVolume = it) }
            Wire.ANC_LEVEL -> if (ack == null || current.ancLevel == null) null else single(0..1)?.let { current.copy(ancLevel = it) }
            Wire.CUSTOM_AMBIENT -> if (ack == null) null else current.customAmbient?.let { old ->
                if (p.size !in setOf(4, 6) || (p.size == 6 && p[4].toInt() !in 0..1) || p[0].toInt() !in 0..1 || p[1].toInt() !in 0..old.maximum ||
                    p[2].toInt() !in 0..old.maximum || p[3].toInt() !in 0..4) null
                else current.copy(customAmbient = SamsungCustomAmbient(p[0].toInt() == 1, p[1].toInt(), p[2].toInt(), p[3].toInt(), old.maximum))
            }
            0x90 -> if (ack == null || current.locked == null) null else bool()?.let { current.copy(locked = it) }
            0x92 -> if (ack == null || !model.ambientOnly || p.size != 2 ||
                p.any { it.toInt() !in (if (model.original) 0..4 else 1..4) }) null
                else current.copy(leftHold = p[0].toInt(), rightHold = p[1].toInt())
            0x85 -> if (ack == null || current.voiceFocus == null) null else bool()?.let { current.copy(voiceFocus = it) }
            Wire.FIND_MUTE_REPORT, Wire.FIND_MUTE -> {
                if (id == Wire.FIND_MUTE && ack == null || p.size != 2 || p.any { it.toInt() !in 0..1 }) null
                else current.copy(findLeftMuted = p[0].toInt() == 1, findRightMuted = p[1].toInt() == 1)
            }
            Wire.FIND_STOP -> if (ack == null && p.isEmpty()) current.copy(findStopped = true) else null
            else -> null
        } ?: return null
        state = next
        return next
    }
}
