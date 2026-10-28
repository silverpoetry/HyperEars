package dev.hyperears.integration

import dev.hyperears.protocol.sony.SonyAudioSettingsCodec as Wire

internal class SonyAudioSettingsSession(private val v2: Boolean) {
    private var selection: Wire.Selection? = null
    var state = SonyAudioSettingsFeatureState()
        private set
    private fun definition(type: Int?) = type?.let { SonyAudioDefinition(if (v2) 2 else 1, it) }
    fun accept(payload: ByteArray): SonyDailyControlsSession.Update? {
        Wire.functions(v2, payload)?.let { next ->
            if (selection == next) return null
            val old = selection; selection = next
            state = state.copy(
                wearingDefinition = definition(next.wearing), wearingPlayback = state.wearingPlayback.takeIf {old?.wearing == next.wearing},
                wearingAvailable = state.wearingAvailable.takeIf {old?.wearing == next.wearing},
                dseeDefinition = definition(next.dsee), dsee = state.dsee.takeIf { old?.dsee == next.dsee },
                dseeGeneration = state.dseeGeneration.takeIf { old?.dsee == next.dsee },
                dseeAvailable = state.dseeAvailable.takeIf { old?.dsee == next.dsee },
                qualityDefinition = definition(next.quality), quality = state.quality.takeIf { old?.quality == next.quality },
                qualityAvailable = state.qualityAvailable.takeIf { old?.quality == next.quality },
                switchingStream = state.switchingStream.takeIf { old?.quality == next.quality },
                speakDefinition = definition(next.speak), speak = state.speak.takeIf { old?.speak == next.speak },
                speakAvailable = state.speakAvailable.takeIf { old?.speak == next.speak },
                speakActive = state.speakActive.takeIf { old?.speak == next.speak })
            return SonyDailyControlsSession.Update(listOf(state), emptyList())
        }
        val selected = selection ?: return null
        Wire.wearing(v2,payload)?.takeIf{it.type==selected.wearing}?.let { report ->
            state=if(report.command in setOf(0xF3,0xF5))state.copy(wearingAvailable=report.value==0)
                else state.copy(wearingPlayback=report.value==1)
            return SonyDailyControlsSession.Update(listOf(state),emptyList())
        }
        val audio = if (v2) Wire.audio(payload) else null
        val next = if (audio != null) when {
            audio.type == selected.dsee -> when (audio.command) {
                0xE1 -> state.copy(dseeGeneration = audio.value)
                0xE3, 0xE5 -> state.copy(dseeAvailable = audio.value == 0)
                else -> state.copy(dsee = audio.value == 1)
            }
            audio.type == selected.quality -> when (audio.command) {
                0xE3, 0xE5 -> state.copy(qualityAvailable = audio.value == 0)
                0xE7, 0xE9 -> state.copy(quality = audio.value, switchingStream = audio.extra)
                else -> return null
            }
            else -> return null
        } else {
            val speak = Wire.speak(v2, payload)?.takeIf { it.type == selected.speak } ?: return null
            if (speak.command in setOf(0xF3, 0xF5)) state.copy(speakAvailable = speak.value == 0, speakActive = speak.extra == 1)
            else state.copy(speak = speak.value == 1)
        }
        state = next
        return SonyDailyControlsSession.Update(listOf(state), emptyList())
    }
    fun reads(): List<ByteArray> = buildList {
        selection?.wearing?.let {add(Wire.bytes(0xF2,it));add(Wire.bytes(0xF6,it))}
        selection?.dsee?.let { add(Wire.bytes(0xE0, it)); add(Wire.bytes(0xE6, it)); if (it == 11) add(Wire.bytes(0xE2, it)) }
        selection?.quality?.let { add(Wire.bytes(0xE2, it)); add(Wire.bytes(0xE6, it)) }
        selection?.speak?.let { add(Wire.bytes(0xF2, it)); add(Wire.bytes(0xF6, it)) }
    }
    fun encode(request: SonyControlRequest): List<ByteArray> {
        if (!state.supports(request)) return emptyList()
        return when (request) {
            is SonyControlRequest.SetWearingPlayback -> listOf(Wire.bytes(0xF8,3,0,if(request.enabled)1 else 0),Wire.bytes(0xF6,3),Wire.bytes(0xF2,3))
            is SonyControlRequest.SetDsee -> listOf(Wire.bytes(0xE8, request.expected.inquiredType, if (request.enabled) 1 else 0),
                Wire.bytes(0xE6, request.expected.inquiredType)) +
                if (request.expected.inquiredType == 11) listOf(Wire.bytes(0xE2, 11)) else emptyList()
            is SonyControlRequest.SetConnectionQuality -> listOf(Wire.bytes(0xE8, request.expected.inquiredType, request.mode),
                Wire.bytes(0xE6, request.expected.inquiredType), Wire.bytes(0xE2, request.expected.inquiredType))
            is SonyControlRequest.SetSpeakToChat -> listOf(
                if (v2) Wire.bytes(0xF8, request.expected.inquiredType, if (request.enabled) 0 else 1, 1)
                else Wire.bytes(0xF8, 5, 1, if (request.enabled) 1 else 0),
                Wire.bytes(0xF6, request.expected.inquiredType), Wire.bytes(0xF2, request.expected.inquiredType))
            else -> emptyList()
        }
    }
}
