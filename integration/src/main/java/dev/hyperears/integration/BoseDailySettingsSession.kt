package dev.hyperears.integration

import dev.hyperears.protocol.bose.BoseBmapWireCodec
import dev.hyperears.protocol.bose.BoseDailySettingsCodec as Wire

internal class BoseDailySettingsSession {
    private var productId: Int? = null
    private var liveAudioPending=false
    private var profile = BoseDailyProfile()
    var state = BoseDailySettingsFeatureState()
        private set
    fun configure(id: Int?) {
        if (productId != id) reset()
        productId = id; profile = BoseDailyProfile.forProduct(id)
    }
    fun reset() { state = BoseDailySettingsFeatureState();liveAudioPending=false }
    fun queries(): List<ByteArray> = buildList {
        if(profile.liveAudio)add(Wire.queryAudioSettings())
        if(profile.rename)add(Wire.query(2))
        if(profile.buttons)add(Wire.query(Wire.BUTTONS))
        if (profile.sidetone) add(Wire.query(Wire.SIDETONE))
        if (profile.autoAnswer) add(Wire.query(Wire.AUTO_ANSWER))
        if (profile.eq) add(Wire.query(Wire.EQ))
        if (profile.prompts) add(Wire.query(Wire.PROMPTS))
        if (profile.multipoint) add(Wire.query(Wire.MULTIPOINT))
        if (profile.autoPause) add(Wire.query(Wire.AUTO_PAUSE))
        if (profile.cnc) add(Wire.query(Wire.CNC))
        if (profile.anr) add(Wire.query(Wire.ANR))
    }
    fun offer(frame: BoseBmapWireCodec.Frame): BoseDailySettingsFeatureState? {
        val next = when {
            profile.liveAudio && frame.functionBlock==31 && frame.function==10 -> Wire.parseAudioSettings(frame)?.let{liveAudioPending=false;state.copy(liveAudio=BoseLiveAudioSettings(requireNotNull(productId),it))}
            profile.rename && frame.functionBlock==1 && frame.function==2 -> Wire.parseDeviceName(frame)?.let{state.copy(deviceName=BoseDeviceName(requireNotNull(productId),it.flag,it.text))}
            profile.buttons && frame.function==Wire.BUTTONS -> Wire.parseButton(frame)?.let { report ->
                val product=productId?:return null
                val value=BoseButtonSetting(product,report.id,report.event,report.current,report.mask)
                state.copy(buttons=state.buttons.filterNot{it.button==value.button&&it.event==value.event}+value)
            }
            profile.sidetone && frame.function == Wire.SIDETONE -> Wire.parseSidetone(frame)?.let { state.copy(sidetone = it) }
            profile.autoAnswer && frame.function == Wire.AUTO_ANSWER -> Wire.parseAutoAnswer(frame)?.let { state.copy(autoAnswer = it) }
            profile.eq && frame.function == Wire.EQ -> Wire.parseEq(frame)?.let { bands -> state.copy(eq = bands.map {
                BoseEqBand(BoseEqBandDefinition(it.id, it.minimum, it.maximum), it.current)
            }) }
            profile.prompts && frame.function == Wire.PROMPTS -> Wire.parsePrompts(frame)?.let { state.copy(promptsRaw = it) }
            profile.multipoint && frame.function == Wire.MULTIPOINT -> Wire.parseMultipoint(frame)?.let { state.copy(multipoint = it) }
            profile.autoPause && frame.function == Wire.AUTO_PAUSE -> Wire.parseAutoPause(frame)?.let { state.copy(autoPause = it) }
            profile.cnc && frame.function == Wire.CNC -> Wire.parseCnc(frame)?.let { state.copy(cnc = BoseCncLevel(it.rawLevel, it.enabled)) }
            profile.anr && frame.function == Wire.ANR -> Wire.parseAnr(frame)?.let { state.copy(anr = it) }
            else -> null
        } ?: return null
        state = next
        return next
    }
    fun encode(request: BoseDailyControlRequest): List<ByteArray> {
        if (!state.supports(request)) return emptyList()
        val wholeAudio=request is BoseDailyControlRequest.SetSpatial || request is BoseDailyControlRequest.SetWind
        if(wholeAudio&&liveAudioPending)return emptyList()
        return when (request) {
            is BoseDailyControlRequest.SetSpatial -> listOf(Wire.setAudioSettings(request.expected.raw,2,request.mode))
            is BoseDailyControlRequest.SetWind -> listOf(Wire.setAudioSettings(request.expected.raw,3,if(request.enabled)1 else 0))
            is BoseDailyControlRequest.Rename -> listOf(Wire.setDeviceName(request.name))
            is BoseDailyControlRequest.SetButton -> listOf(Wire.setButton(request.expected.button,request.expected.event,request.action))
            is BoseDailyControlRequest.SetSidetone -> listOf(Wire.setSidetone(request.level))
            is BoseDailyControlRequest.SetAutoAnswer -> listOf(Wire.setToggle(Wire.AUTO_ANSWER, request.enabled))
            is BoseDailyControlRequest.SetEqBand -> listOf(Wire.setEq(request.expected.id, request.value))
            is BoseDailyControlRequest.SetVoicePrompts -> listOf(Wire.setPrompts(state.promptsRaw!!, request.enabled))
            is BoseDailyControlRequest.SetMultipoint -> listOf(Wire.setToggle(Wire.MULTIPOINT, request.enabled))
            is BoseDailyControlRequest.SetAutoPause -> listOf(Wire.setToggle(Wire.AUTO_PAUSE, request.enabled))
            is BoseDailyControlRequest.SetAnr -> listOf(BoseBmapWireCodec.setAnr(request.value))
            is BoseDailyControlRequest.SetCncLevel -> {
                val command = BoseBmapWireCodec.setCnc(request.rawLevel, true)
                if (profile.repeatCncEnable && state.cnc?.enabled == false && request.rawLevel != 0)
                    listOf(command, command) else listOf(command)
            }
        }.also {if(wholeAudio)liveAudioPending=true}
    }
    fun readback(request: BoseDailyControlRequest): List<ByteArray> = if(request is BoseDailyControlRequest.SetSpatial || request is BoseDailyControlRequest.SetWind) listOf(Wire.queryAudioSettings()) else listOf(Wire.query(when (request) {
        is BoseDailyControlRequest.SetSpatial,is BoseDailyControlRequest.SetWind -> error("handled audio block")
        is BoseDailyControlRequest.Rename -> 2
        is BoseDailyControlRequest.SetButton -> Wire.BUTTONS
        is BoseDailyControlRequest.SetSidetone -> Wire.SIDETONE
        is BoseDailyControlRequest.SetAutoAnswer -> Wire.AUTO_ANSWER
        is BoseDailyControlRequest.SetEqBand -> Wire.EQ
        is BoseDailyControlRequest.SetVoicePrompts -> Wire.PROMPTS
        is BoseDailyControlRequest.SetMultipoint -> Wire.MULTIPOINT
        is BoseDailyControlRequest.SetAutoPause -> Wire.AUTO_PAUSE
        is BoseDailyControlRequest.SetCncLevel -> Wire.CNC
        is BoseDailyControlRequest.SetAnr -> Wire.ANR
    }))
}
