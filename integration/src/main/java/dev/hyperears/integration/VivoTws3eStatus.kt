package dev.hyperears.integration

import kotlinx.serialization.json.*

/** TWS 3e A102|10084 report, not the unrelated GAIA parameter-response dialect. */
internal object VivoTws3eStatus {
    data class Report(val settings: VivoTws3eFeatureState, val battery: EarbudBattery?, val noise: NoiseMode?)
    fun parse(text: String): Report? = runCatching {
        val root = Json.parseToJsonElement(text).jsonObject
        require(root["eventId"]?.jsonPrimitive?.content == "A102|10084")
        val params = root["params"]?.jsonObject ?: return null
        fun number(key: String): Int? {
            val value = params[key] ?: return null
            val primitive = value as? JsonPrimitive ?: error("Invalid setting")
            val raw = primitive.content
            require(raw.matches(Regex("[0-9]{1,3}")))
            return raw.toInt()
        }
        fun <T> mapped(key: String, choices: Iterable<T>, value: (T) -> Int): T? {
            val reported = number(key) ?: return null
            return choices.firstOrNull { value(it) == reported } ?: error("Unknown setting value")
        }
        val settings = VivoTws3eFeatureState(
            leftTap = mapped("double_click_l", VivoDoubleTap.entries) { it.report },
            rightTap = mapped("double_click_r", VivoDoubleTap.entries) { it.report },
            leftHold = mapped("long_click_l", VivoHoldCycle.entries) { it.wire },
            rightHold = mapped("long_click_r", VivoHoldCycle.entries) { it.wire },
            deepX = mapped("eq_type", VivoDeepX.entries) { it.wire },
            wearDetection = number("wear_enable")?.let { require(it in 0..1); it == 1 },
        )
        val noise = number("noise")?.let {
            when (it) { 0 -> NoiseMode.ANC; 1 -> NoiseMode.OFF; 2 -> NoiseMode.TRANSPARENCY
                else -> error("Unknown noise mode") }
        }
        val battery = root["C"]?.jsonPrimitive?.content?.let { raw ->
            val values = raw.split('_')
            require(values.size == 2)
            val percentages = values.map { it.toInt().also { n -> require(n in 0..100) } }
            EarbudBattery(left = BatteryReading(percentages[0], false), right = BatteryReading(percentages[1], false))
        }
        require(settings.hasSettings || settings.leftHold != null || settings.rightHold != null ||
            battery != null || noise != null)
        Report(settings, battery, noise)
    }.getOrNull()
}

