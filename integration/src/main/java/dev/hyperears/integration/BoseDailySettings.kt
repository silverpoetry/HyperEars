package dev.hyperears.integration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class BoseButtonSetting(val productId:Int,val button:Int,val event:Int,val current:Int,val mask:List<Int>) {
    val actions:List<Int> get()=dev.hyperears.protocol.bose.BoseDailySettingsCodec.buttonActions.filter {id ->
        mask.getOrNull(id/8)?.let{it and (1 shl (id%8)) != 0} == true
    }.sorted()
}
@Serializable
data class BoseEqBandDefinition(val id: Int, val minimum: Int, val maximum: Int)
@Serializable
data class BoseEqBand(val definition: BoseEqBandDefinition, val current: Int)
@Serializable
data class BoseCncLevel(val rawLevel: Int, val enabled: Boolean)
@Serializable
@SerialName("bose.daily_settings")
data class BoseDailySettingsFeatureState(
    val eq: List<BoseEqBand>? = null,
    val promptsRaw: Int? = null,
    val multipoint: Boolean? = null,
    val autoPause: Boolean? = null,
    val cnc: BoseCncLevel? = null,
    val anr: Int? = null,
    val sidetone: Int? = null,
    val autoAnswer: Boolean? = null,
    val buttons: List<BoseButtonSetting> = emptyList(),
) : DeviceFeatureState {
    @Transient override val featureId = FEATURE_ID
    val hasSettings get() = !eq.isNullOrEmpty() || promptsRaw != null || multipoint != null ||
        autoPause != null || cnc != null || anr != null || sidetone != null || autoAnswer != null || buttons.isNotEmpty()
    fun supports(request: BoseDailyControlRequest): Boolean = when (request) {
        is BoseDailyControlRequest.SetButton -> request.expected in buttons && request.action in request.expected.actions && request.action!=request.expected.current
        is BoseDailyControlRequest.SetEqBand -> eq?.any { it.definition == request.expected &&
            request.value in it.definition.minimum..it.definition.maximum } == true
        is BoseDailyControlRequest.SetVoicePrompts -> promptsRaw != null
        is BoseDailyControlRequest.SetMultipoint -> multipoint != null
        is BoseDailyControlRequest.SetAutoPause -> autoPause != null
        is BoseDailyControlRequest.SetCncLevel -> cnc != null && request.rawLevel in 0..10
        is BoseDailyControlRequest.SetSidetone -> sidetone != null && request.level in 0..3
        is BoseDailyControlRequest.SetAutoAnswer -> autoAnswer != null
        is BoseDailyControlRequest.SetAnr -> anr != null && request.value in 0..3
    }
    companion object { const val FEATURE_ID = "bose.daily_settings" }
}
@Serializable
sealed interface BoseDailyControlRequest : ControlRequest {
    @Serializable @SerialName("bose.set_button")
    data class SetButton(val expected:BoseButtonSetting,val action:Int):BoseDailyControlRequest
    @Serializable @SerialName("bose.set_sidetone")
    data class SetSidetone(val level: Int) : BoseDailyControlRequest
    @Serializable @SerialName("bose.set_auto_answer")
    data class SetAutoAnswer(val enabled: Boolean) : BoseDailyControlRequest
    @Serializable @SerialName("bose.set_eq_band")
    data class SetEqBand(val expected: BoseEqBandDefinition, val value: Int) : BoseDailyControlRequest
    @Serializable @SerialName("bose.set_voice_prompts")
    data class SetVoicePrompts(val enabled: Boolean) : BoseDailyControlRequest
    @Serializable @SerialName("bose.set_multipoint")
    data class SetMultipoint(val enabled: Boolean) : BoseDailyControlRequest
    @Serializable @SerialName("bose.set_auto_pause")
    data class SetAutoPause(val enabled: Boolean) : BoseDailyControlRequest
    @Serializable @SerialName("bose.set_cnc_level")
    data class SetCncLevel(val rawLevel: Int) : BoseDailyControlRequest
    @Serializable @SerialName("bose.set_anr")
    data class SetAnr(val value: Int) : BoseDailyControlRequest
}

/** Query eligibility from source device implementations, never control admission by product alone. */
internal data class BoseDailyProfile(val eq: Boolean = false, val prompts: Boolean = false,
    val multipoint: Boolean = false, val autoPause: Boolean = false, val cnc: Boolean = false,
    val anr: Boolean = false, val repeatCncEnable: Boolean = false,
    val sidetone: Boolean = false, val autoAnswer: Boolean = false, val buttons:Boolean=false) {
    companion object {
        fun forProduct(id: Int?): BoseDailyProfile = when (id) {
            0x400C -> BoseDailyProfile(prompts = true, anr = true)
            0x4020 -> BoseDailyProfile(buttons=true, prompts = true, anr = true, sidetone = true)
            0x4024 -> BoseDailyProfile(cnc = true, repeatCncEnable = true)
            0x4039 -> BoseDailyProfile(buttons=true, eq = true, prompts = true, multipoint = true, sidetone = true)
            0x4075 -> BoseDailyProfile(eq = true, prompts = true)
            0x402F -> BoseDailyProfile(buttons=true, eq = true, prompts = true, multipoint = true, cnc = true, sidetone = true)
            0x4082 -> BoseDailyProfile(buttons=true, eq = true, prompts = true, multipoint = true, autoPause = true, sidetone = true, autoAnswer = true)
            0x4062 -> BoseDailyProfile(eq = true, prompts = true, multipoint = true, autoPause = true)
            0x4068 -> BoseDailyProfile(eq = true, prompts = true, multipoint = true)
            else -> BoseDailyProfile()
        }
    }
}

object BoseDailyPresentationIds {
    val TWO_MODE = MiLinkCardPresentationId("bose-daily-two-mode")
    val WIND_OFF = MiLinkCardPresentationId("bose-daily-wind-off")
    val WIND_TRANSPARENCY = MiLinkCardPresentationId("bose-daily-wind-transparency")
    val THREE_MODE = MiLinkCardPresentationId("bose-daily-three-mode")
    val SETTINGS_ONLY = MiLinkCardPresentationId("bose-daily-settings-only")
    fun base(id: MiLinkCardPresentationId?): MiLinkCardPresentationId? = when (id) {
        TWO_MODE -> BoseMiLinkPresentationIds.TWO_MODE
        WIND_OFF -> BoseMiLinkPresentationIds.WIND_REPLACES_OFF
        WIND_TRANSPARENCY -> BoseMiLinkPresentationIds.WIND_REPLACES_TRANSPARENCY
        THREE_MODE, SETTINGS_ONLY -> null
        else -> id
    }
    fun withSettings(id: MiLinkCardPresentationId?, noise: Boolean): MiLinkCardPresentationId = when (base(id)) {
        BoseMiLinkPresentationIds.TWO_MODE -> TWO_MODE
        BoseMiLinkPresentationIds.WIND_REPLACES_OFF -> WIND_OFF
        BoseMiLinkPresentationIds.WIND_REPLACES_TRANSPARENCY -> WIND_TRANSPARENCY
        else -> if (noise) THREE_MODE else SETTINGS_ONLY
    }
}
