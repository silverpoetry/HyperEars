package dev.hyperears.integration

import java.util.UUID
import kotlinx.serialization.json.*
import kotlinx.serialization.Serializable

@Serializable
enum class RayNeoLevelSetting(val label: String, val stream: String?) {
    BRIGHTNESS("眼镜亮度",null), MEDIA_VOLUME("媒体音量","3"), SYSTEM_VOLUME("系统提示音音量","1");
    // ARGF20 live AudioService reports max=15 for streams 1 and 3. Its AudioHelper
    // returns floor(index*100/max), but writes rounded(percent*max/100).
    val range: IntRange get() = if(this == BRIGHTNESS) 0..100 else 0..15
    fun displayValue(percent: Int): Int = if(this == BRIGHTNESS) percent else (percent*15+50)/100
    fun wireValue(value: Int): Int {
        require(value in range)
        return if(this == BRIGHTNESS) value else (value*100+14)/15
    }
    fun value(state: RayNeoSettingsFeatureState): Int? = when(this) {
        BRIGHTNESS -> state.brightness
        MEDIA_VOLUME -> state.mediaVolume
        SYSTEM_VOLUME -> state.systemVolume
    }
}

sealed interface RayNeoSettingsReply {
    data class Status(val battery: BatteryReading?, val wifiEnabled: Boolean?, val deepSuspend: Boolean?,
        val raw: JsonObject, val brightness: Int? = null, val automaticBrightness: Boolean? = null,
        val mediaVolume: Int? = null, val systemVolume: Int? = null, val voiceMode: Int? = null) : RayNeoSettingsReply
    data class Settings(val x3Pro: Boolean, val autoLockSeconds: Int?, val supportMixShot: Boolean,
        val supportMixRecord: Boolean) : RayNeoSettingsReply
}

/** Legacy direct-field envelopes; full status snapshots remain session-private. */
object RayNeoSettingsCodec {
    val sleepTimes = setOf(-1, 15, 30, 45, 60)
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
    private fun JsonObject.bool(key: String) = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    fun parse(message: String): RayNeoSettingsReply? = runCatching {
        val root = Json.parseToJsonElement(message).jsonObject
        when (root["uri"]?.jsonPrimitive?.content) {
            "ble.request.general.status" -> {
                val status = root.getValue("generalStatus").jsonObject
                val percent = status.number("battery")?.takeIf { it in 0..100 }
                val charging = status.bool("isCharging")
                val volumes = status["volumes"] as? JsonObject
                RayNeoSettingsReply.Status(
                    if (percent != null && charging != null) BatteryReading(percent, charging) else null,
                    status.bool("wifiEnabled"), status.number("deepSuspend")?.takeIf { it in 0..1 }?.let { it == 1 }, status,
                    status.number("brightness")?.takeIf { it in 0..100 },status.bool("automaticBrightness"),
                    volumes?.number("3")?.takeIf { it in 0..100 },volumes?.number("1")?.takeIf { it in 0..100 },
                    status.number("voiceMode")?.takeIf { it in 0..2 })
            }
            "ble.request.general.settings" -> {
                val settings = root.getValue("generalSettings").jsonObject
                RayNeoSettingsReply.Settings(settings["deviceType"]?.jsonPrimitive?.content == "RayNeoX3Pro" &&
                    settings["productModel"]?.jsonPrimitive?.content == "ARGF20",
                    settings.number("autoLockTime")?.takeIf { it in sleepTimes },
                    settings.bool("supportMixShot") == true,settings.bool("supportMixRecord") == true)
            }
            else -> null
        }
    }.getOrNull()

    private fun envelope(uri: String, fields: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("uri",uri); put("id",UUID.randomUUID().toString()); fields()
    }.toString()
    fun statusQuery() = envelope("ble.response.pull.general_status")
    fun settingsQuery() = envelope("ble.response.pull.general_settings")
    fun autoLock(seconds: Int): String {
        require(seconds in sleepTimes)
        return envelope("ble.response.auto_lock_time") { put("time",seconds) }
    }
    fun wifi(enabled: Boolean, countryCode: String): String {
        require(countryCode.isEmpty() || Regex("[A-Z]{2}").matches(countryCode))
        return envelope("ble.response.wifi.toggle") { put("enable",enabled); put("countryCode",countryCode) }
    }
    fun deepSuspend(snapshot: JsonObject, enabled: Boolean): String {
        require(snapshot.number("deepSuspend") in 0..1)
        return envelope("ble.response.general.status.update") {
            put("generalStatus",JsonObject(snapshot + ("deepSuspend" to JsonPrimitive(if(enabled) 1 else 0))))
        }
    }
    fun automaticBrightness(snapshot: JsonObject, enabled: Boolean): String {
        require(snapshot.bool("automaticBrightness") != null)
        return envelope("ble.response.general.status.update") {
            put("generalStatus",JsonObject(snapshot + ("automaticBrightness" to JsonPrimitive(enabled))))
        }
    }
    fun voiceMode(snapshot: JsonObject, value: Int): String {
        require(snapshot.number("voiceMode") in 0..2 && value in 0..2)
        return envelope("ble.response.general.status.update") {
            put("generalStatus",JsonObject(snapshot + ("voiceMode" to JsonPrimitive(value))))
        }
    }
    fun level(snapshot: JsonObject, setting: RayNeoLevelSetting, value: Int): String {
        require(value in 0..100)
        val changed = if(setting == RayNeoLevelSetting.BRIGHTNESS) {
            require(snapshot.number("brightness") in 0..100 && snapshot.bool("automaticBrightness") == false)
            snapshot + ("brightness" to JsonPrimitive(value))
        } else {
            val volumes = snapshot.getValue("volumes").jsonObject
            val stream = requireNotNull(setting.stream)
            require(volumes.number(stream) in 0..100)
            snapshot + ("volumes" to JsonObject(volumes + (stream to JsonPrimitive(value))))
        }
        return envelope("ble.response.general.status.update") { put("generalStatus",JsonObject(changed)) }
    }
}
