package dev.hyperears.integration

/** Per-model executable-handler membership; this permits queries, never enables controls. */
internal data class HuaweiDailyProfile(
    val wear: Boolean = false,
    val multipoint: Boolean = false,
    val presets: List<Int> = emptyList(),
    val custom: Boolean = false,
    val gestures: Map<HuaweiGestureKind, GestureProfile> = emptyMap(),
) {
    data class GestureProfile(val values: List<Int>, val right: Boolean = true, val call: Boolean = false)
    companion object {
        private val tap = GestureProfile(listOf(-1, 1, 2, 7, 0))
        private val hold = GestureProfile(listOf(-1, 10))
        private val cycle = GestureProfile(listOf(1, 2, 3, 4))
        private val swipe = GestureProfile(listOf(-1, 0), right = false)
        val PRO3 = HuaweiDailyProfile(true, true, listOf(5, 1, 2, 9), custom = true,
            gestures = mapOf(HuaweiGestureKind.DOUBLE to tap, HuaweiGestureKind.HOLD to hold,
                HuaweiGestureKind.CYCLE to cycle, HuaweiGestureKind.SWIPE to swipe))
        val BUDS5I = HuaweiDailyProfile(true, true, listOf(1, 2, 3, 9), gestures = mapOf(
            HuaweiGestureKind.DOUBLE to tap.copy(call = true), HuaweiGestureKind.TRIPLE to tap,
            HuaweiGestureKind.HOLD to hold, HuaweiGestureKind.CYCLE to cycle, HuaweiGestureKind.SWIPE to swipe))
        val BUDS6I = BUDS5I.copy(custom = true, gestures = BUDS5I.gestures + mapOf(
            HuaweiGestureKind.DOUBLE to GestureProfile(listOf(1, 2), call = true),
            HuaweiGestureKind.TRIPLE to GestureProfile(listOf(2, 7, -1))))
        val BUDS4I = HuaweiDailyProfile(wear = true, gestures = mapOf(
            HuaweiGestureKind.DOUBLE to tap, HuaweiGestureKind.HOLD to hold.copy(right = false),
            HuaweiGestureKind.CYCLE to cycle.copy(right = false)))
        // HuaweiPods captured FreeClip2 effect IDs take precedence over the generic desktop list.
        val FREECLIP2 = HuaweiDailyProfile(true, true, listOf(1, 10, 3, 9), gestures = mapOf(
            HuaweiGestureKind.DOUBLE to tap, HuaweiGestureKind.TRIPLE to GestureProfile(listOf(2, 7, -1)),
            HuaweiGestureKind.HOLD to GestureProfile(listOf(-1, 0, 18, 19), right = false),
            HuaweiGestureKind.SWIPE to swipe))
        val PRO = HuaweiDailyProfile(multipoint = true, gestures = mapOf(
            HuaweiGestureKind.HOLD to hold, HuaweiGestureKind.CYCLE to cycle, HuaweiGestureKind.SWIPE to swipe))
        val PRO2 = PRO.copy(wear = true, presets = listOf(1, 2, 3))
        val STUDIO = HuaweiDailyProfile(wear = true) // EQ explicitly wo_read upstream.
        val LACE_PRO2 = HuaweiDailyProfile(multipoint = true, custom = true,
            // Built-ins must be advertised; no model-specific preset labels are inferred.
            gestures = mapOf(HuaweiGestureKind.HOLD to hold.copy(right = false),
                HuaweiGestureKind.CYCLE to cycle.copy(right = false)))
    }
}

internal object HuaweiDailyModelCatalog {
    data class Model(val id: String, val name: String, val channel: Int,
        val daily: HuaweiDailyProfile, val aggregate: Boolean = false) {
        val aliases get() = setOf(name, name.removePrefix("HUAWEI "))
    }
    val models = listOf(
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
    endpointPrefix = model.id, channelNumbers = listOf(model.channel),
    profile = HuaweiProtocolProfile(setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
        daily = model.daily, aggregateBattery = model.aggregate),
) {
    override val id = model.id
    override val displayName = model.name
    override val resolution = AdapterResolution.EXACT_MATCH
    override val formFactor = if (model.aggregate) HeadsetFormFactor.HEADPHONES else HeadsetFormFactor.TWS
    override fun matches(identity: EarbudIdentity): Boolean = super.matches(identity) &&
        model.aliases.any { normalizeDeviceName(it) == normalizeDeviceName(identity.deviceName.orEmpty()) }
}
