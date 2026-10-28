package dev.hyperears.integration

/** Per-model executable-handler membership; this permits queries, never enables controls. */
internal data class HuaweiDailyProfile(
    val wear: Boolean = false,
    val multipoint: Boolean = false,
    val presets: List<Int> = emptyList(),
    val custom: Boolean = false,
    val gestures: Map<HuaweiGestureKind, GestureProfile> = emptyMap(),
    val lowLatency: Boolean = false,
    val soundQuality: Boolean = false,
    val autoConnect:Boolean=true,
) {
    data class GestureProfile(val values: List<Int>, val right: Boolean = true, val call: Boolean = false, val left: Boolean = true, val mirrored: Boolean = false)
    companion object {
        private val tap = GestureProfile(listOf(-1, 1, 2, 7, 0))
        private val hold = GestureProfile(listOf(-1, 10))
        private val cycle = GestureProfile(listOf(1, 2, 3, 4))
        private val swipe = GestureProfile(listOf(-1, 0), right = false)
        val PRO3 = HuaweiDailyProfile(true, true, listOf(5, 1, 2, 9), custom = true, lowLatency = true, soundQuality = true,
            gestures = mapOf(HuaweiGestureKind.DOUBLE to tap, HuaweiGestureKind.HOLD to hold,
                HuaweiGestureKind.CYCLE to cycle, HuaweiGestureKind.SWIPE to swipe))
        // OpenFreebuds routes these models to Pro3: query candidates, not hardware validation.
        // HuaweiPods qualifies Pro4 basic ANC only; exclude transparency from gesture cycles too.
        val PRO4 = PRO3.copy(gestures = PRO3.gestures +
            (HuaweiGestureKind.CYCLE to GestureProfile(listOf(1))))
        // FreeClip has no ANC. The alias driver's HOLD only proves -1/10, so keep -1 only;
        // do not invent assistant/volume actions from newer FreeClip2 firmware.
        val FREECLIP = PRO3.copy(gestures = PRO3.gestures - HuaweiGestureKind.CYCLE +
            (HuaweiGestureKind.HOLD to GestureProfile(listOf(-1))))
        val BUDS5I = HuaweiDailyProfile(true, true, listOf(1, 2, 3, 9), lowLatency = true, soundQuality = true, gestures = mapOf(
            HuaweiGestureKind.DOUBLE to tap.copy(call = true), HuaweiGestureKind.TRIPLE to tap,
            HuaweiGestureKind.HOLD to hold, HuaweiGestureKind.CYCLE to cycle, HuaweiGestureKind.SWIPE to swipe))
        val BUDS6I = BUDS5I.copy(autoConnect=false,custom = true, gestures = BUDS5I.gestures + mapOf(
            HuaweiGestureKind.DOUBLE to GestureProfile(listOf(1, 2), call = true),
            HuaweiGestureKind.TRIPLE to GestureProfile(listOf(2, 7, -1))))
        val BUDS4I = HuaweiDailyProfile(wear = true, gestures = mapOf(
            HuaweiGestureKind.DOUBLE to tap, HuaweiGestureKind.HOLD to hold.copy(right = false),
            HuaweiGestureKind.CYCLE to cycle.copy(right = false)))
        // HuaweiPods captured FreeClip2 effect IDs take precedence over the generic desktop list.
        val FREECLIP2 = HuaweiDailyProfile(true, true, listOf(1, 10, 3, 9), lowLatency = true, soundQuality = true, gestures = mapOf(
            HuaweiGestureKind.DOUBLE to tap, HuaweiGestureKind.TRIPLE to GestureProfile(listOf(2, 7, -1)),
            HuaweiGestureKind.HOLD to GestureProfile(listOf(-1, 0, 18, 19), right = false),
            HuaweiGestureKind.SWIPE to swipe))
        val LACE_PRO = HuaweiDailyProfile(gestures = mapOf(
            HuaweiGestureKind.DOUBLE to GestureProfile(listOf(-1, 12), right = false, mirrored = true),
            HuaweiGestureKind.HOLD to GestureProfile(listOf(-1, 3, 5, 6, 9), right = false, mirrored = true)))
        val SE = HuaweiDailyProfile(gestures = mapOf(HuaweiGestureKind.DOUBLE to tap))
        val BUDS3 = HuaweiDailyProfile(gestures = mapOf(
            HuaweiGestureKind.DOUBLE to GestureProfile(listOf(-1, 0, 1, 3, 4))))
        val SE2 = HuaweiDailyProfile(presets = listOf(1, 2, 3, 9), lowLatency = true, gestures = mapOf(
            HuaweiGestureKind.DOUBLE to tap.copy(call = true), HuaweiGestureKind.TRIPLE to tap,
            HuaweiGestureKind.HOLD to hold.copy(left = false, right = false, call = true)))
        val SE4 = SE2.copy(gestures = SE2.gestures + mapOf(
            HuaweiGestureKind.HOLD to GestureProfile(listOf(-1, 10, 0, 18, 19)), HuaweiGestureKind.CYCLE to cycle))
        val PRO = HuaweiDailyProfile(multipoint = true, gestures = mapOf(
            HuaweiGestureKind.HOLD to hold, HuaweiGestureKind.CYCLE to cycle, HuaweiGestureKind.SWIPE to swipe))
        val PRO2 = PRO.copy(wear = true, presets = listOf(1, 2, 3), soundQuality = true)
        val STUDIO = HuaweiDailyProfile(wear = true) // EQ explicitly wo_read upstream.
        val LACE_PRO2 = HuaweiDailyProfile(autoConnect=false,multipoint = true, custom = true, lowLatency = true, soundQuality = true,
            // Built-ins must be advertised; no model-specific preset labels are inferred.
            gestures = mapOf(HuaweiGestureKind.HOLD to hold.copy(right = false),
                HuaweiGestureKind.CYCLE to cycle.copy(right = false)))
    }
}

internal object HuaweiDailyModelCatalog {
    data class Model(val id: String, val name: String, val channel: Int,
        val daily: HuaweiDailyProfile, val aggregate: Boolean = false,
        val noiseModes: Set<NoiseMode> = setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
        val levels: Set<HuaweiAncLevel> = emptySet(), val connectDelayMs: Long = 0L,
        val formFactor: HeadsetFormFactor = if (aggregate) HeadsetFormFactor.HEADPHONES else HeadsetFormFactor.TWS) {
        val aliases get() = setOf(name, name.removePrefix("HUAWEI "))
    }
    val models = listOf(
        Model("huawei-freelace-pro", "HUAWEI FreeLace Pro", 16, HuaweiDailyProfile.LACE_PRO,
            levels = setOf(HuaweiAncLevel.NORMAL, HuaweiAncLevel.COMFORT, HuaweiAncLevel.ULTRA), connectDelayMs = 2_000L, formFactor = HeadsetFormFactor.HEADPHONES),
        Model("huawei-freebuds-pro4", "HUAWEI FreeBuds Pro 4", 1, HuaweiDailyProfile.PRO4,
            noiseModes = setOf(NoiseMode.ANC, NoiseMode.OFF)),
        Model("huawei-freeclip", "HUAWEI FreeClip", 1, HuaweiDailyProfile.FREECLIP, noiseModes = emptySet()),
        Model("huawei-freebuds-3", "HUAWEI FreeBuds 3", 1, HuaweiDailyProfile.BUDS3, noiseModes = emptySet()),
        Model("huawei-freebuds-se", "HUAWEI FreeBuds SE", 16, HuaweiDailyProfile.SE, noiseModes = emptySet()),
        Model("huawei-freebuds-se2", "HUAWEI FreeBuds SE 2", 1, HuaweiDailyProfile.SE2, noiseModes = emptySet()),
        Model("huawei-freebuds-se4-anc", "HUAWEI FreeBuds SE 4 ANC", 1, HuaweiDailyProfile.SE4,
            levels = HuaweiAncLevel.entries.filter { it.domainNoiseMode() == NoiseMode.ANC }.toSet()),
        Model("huawei-freebuds-pro5", "HUAWEI FreeBuds Pro 5", 1, HuaweiDailyProfile.PRO3,
            levels = HuaweiAncLevel.entries.toSet()),
        Model("huawei-freebuds-4i", "HUAWEI FreeBuds 4i", 16, HuaweiDailyProfile.BUDS4I),
        Model("honor-earbuds-2", "HONOR Earbuds 2", 16, HuaweiDailyProfile.BUDS4I),
        Model("honor-earbuds-2-se", "HONOR Earbuds 2 SE", 16, HuaweiDailyProfile.BUDS4I),
        Model("honor-earbuds-2-lite", "HONOR Earbuds 2 Lite", 16, HuaweiDailyProfile.BUDS4I),
        Model("huawei-freebuds-6i", "HUAWEI FreeBuds 6i", 1, HuaweiDailyProfile.BUDS6I),
        Model("huawei-freebuds-pro", "HUAWEI FreeBuds Pro", 1, HuaweiDailyProfile.PRO),
        Model("huawei-freebuds-pro2", "HUAWEI FreeBuds Pro 2", 1, HuaweiDailyProfile.PRO2),
        Model("huawei-freebuds-studio", "HUAWEI FreeBuds Studio", 1, HuaweiDailyProfile.STUDIO, true),
        Model("huawei-freelace-pro2", "HUAWEI FreeLace Pro 2", 1, HuaweiDailyProfile.LACE_PRO2, true),
    )
}

internal class HuaweiDailyModelAdapter(private val model: HuaweiDailyModelCatalog.Model) : HuaweiFreebudsProtocolAdapter(
    endpointPrefix = model.id, channelNumbers = listOf(model.channel), connectDelayMs = model.connectDelayMs,
    profile = HuaweiProtocolProfile(model.noiseModes,
        extension = model.levels.takeIf { it.isNotEmpty() }?.let(::HuaweiLevelProtocolExtension),
        daily = model.daily, aggregateBattery = model.aggregate),
) {
    override val id = model.id
    override val displayName = model.name
    override val resolution = AdapterResolution.EXACT_MATCH
    override val formFactor = model.formFactor
    override fun matches(identity: EarbudIdentity): Boolean = super.matches(identity) &&
        model.aliases.any { normalizeDeviceName(it) == normalizeDeviceName(identity.deviceName.orEmpty()) }
}
