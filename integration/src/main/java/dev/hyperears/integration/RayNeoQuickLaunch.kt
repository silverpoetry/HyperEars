package dev.hyperears.integration

import dev.hyperears.protocol.rayneo.RayNeoBleWireCodec
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Owner-selected defaults, verified against the installed X3 Pro packages.
 * These presets do not imply that a device session supports remote launching.
 */
@Serializable
enum class RayNeoQuickLaunch(val label: String, val packageName: String) {
    @SerialName("bbll") BBLL("BBLL", "com.wayneshao.bbll"),
    @SerialName("mydv") MY_DV("myDV", "com.github.mytv.dv"),
    @SerialName("hongguotv") HONGGUO_TV("红果TV", "com.hongguotv.rayneo"),
    @SerialName("floating_window") FLOATING_WINDOW("浮窗", "com.rayneo.airscreen"),
}

@Serializable
sealed interface RayNeoControlRequest : ControlRequest {
    @Serializable
    @SerialName("rayneo.launch_app")
    data class LaunchApp(val app: RayNeoQuickLaunch) : RayNeoControlRequest
    @Serializable @SerialName("rayneo.launch_installed_app")
    data class LaunchInstalledApp(val packageName: String) : RayNeoControlRequest
    @Serializable @SerialName("rayneo.wifi")
    data class SetWifi(val expected: Boolean, val enabled: Boolean, val countryCode: String) : RayNeoControlRequest
    @Serializable @SerialName("rayneo.deep_suspend")
    data class SetDeepSuspend(val expected: Boolean, val enabled: Boolean) : RayNeoControlRequest
    @Serializable @SerialName("rayneo.auto_lock")
    data class SetAutoLock(val expected: Int, val seconds: Int) : RayNeoControlRequest
    @Serializable @SerialName("rayneo.level")
    data class SetLevel(val setting: RayNeoLevelSetting, val expected: Int, val value: Int) : RayNeoControlRequest
    @Serializable @SerialName("rayneo.automatic_brightness")
    data class SetAutomaticBrightness(val expected: Boolean, val enabled: Boolean) : RayNeoControlRequest
    @Serializable @SerialName("rayneo.voice_mode")
    data class SetVoiceMode(val expected: Int, val value: Int) : RayNeoControlRequest
    @Serializable @SerialName("rayneo.capture.shot")
    data object CaptureShot : RayNeoControlRequest
    @Serializable @SerialName("rayneo.capture.record")
    data class SetRecording(val start: Boolean) : RayNeoControlRequest
    @Serializable @SerialName("rayneo.wifi.scan")
    data class ScanWifi(val countryCode: String) : RayNeoControlRequest
    @Serializable @SerialName("rayneo.wifi.connect")
    data class ConnectWifi(val network: RayNeoWifiNetwork, val password: String?) : RayNeoControlRequest {
        override fun toString() = "ConnectWifi(network=<selected>, password=<redacted>)"
    }
}

/** Legacy AppMarket JSON over GATT, not the newer CoreConnect protobuf format.
 * The per-connection session gates installed packages and tracks launch_status.
 * Successful serialization/write is not launch success.
 */
object RayNeoLaunchCodec {
    fun parse(message: String): RayNeoLaunchReply? = runCatching {
        val root = Json.parseToJsonElement(message).jsonObject
        if (root["uri"]?.jsonPrimitive?.content != "ble.request.app_market") return null
        val payload = root.getValue("payload").jsonObject
        val dataString = payload["data"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: return null
        when (payload["cmd"]?.jsonPrimitive?.content) {
            "installed_apps" -> {
                val array = Json.parseToJsonElement(dataString).jsonArray
                if (array.size > 2048) return null
                val packages = array.map {
                    it.jsonPrimitive.takeIf { value -> value.isString }?.content ?: return null
                }
                if (packages.any { !PACKAGE_NAME.matches(it) }) return null
                RayNeoLaunchReply.InstalledApps(packages.toSet())
            }
            "launch_status" -> {
                val data = Json.parseToJsonElement(dataString).jsonObject
                val pkg = data.getValue("pkg").jsonPrimitive.takeIf { it.isString }?.content ?: return null
                val status = data.getValue("status").jsonPrimitive.takeUnless { it.isString }?.intOrNull ?: return null
                if (!PACKAGE_NAME.matches(pkg) || status !in -1..1) return null
                RayNeoLaunchReply.LaunchStatus(pkg, status)
            }
            else -> null // Including ACK: it proves receipt, not successful launch.
        }
    }.getOrNull()

    fun installedAppsQuery(id: String = UUID.randomUUID().toString()): String = buildJsonObject {
        require(UUID.fromString(id).toString() == id)
        put("uri", "ble.response.app_market")
        put("id", id)
        putJsonObject("payload") { put("cmd", "installed_apps") }
    }.toString()

    private val PACKAGE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")

    fun message(app: RayNeoQuickLaunch, id: String = UUID.randomUUID().toString()): String = message(app.packageName,id)
    fun message(packageName: String, id: String = UUID.randomUUID().toString()): String {
        require(packageName.length <= 255 && PACKAGE_NAME.matches(packageName))
        require(UUID.fromString(id).toString() == id) { "Canonical message UUID required" }
        val launchData = buildJsonObject { put("pkg", packageName) }
        return buildJsonObject {
            put("uri", "ble.response.app_market")
            put("id", id)
            putJsonObject("payload") {
                put("cmd", "launch")
                // The protocol embeds JSON as a string rather than an object.
                put("data", launchData.toString())
            }
        }.toString()
    }

    fun fragment(message: String): List<ByteArray> = RayNeoBleWireCodec.encode(message)
}

sealed interface RayNeoLaunchReply {
    data class InstalledApps(val packages: Set<String>) : RayNeoLaunchReply
    /** 0 = launched, 1 = already foreground, -1 = failed. */
    data class LaunchStatus(val packageName: String, val status: Int) : RayNeoLaunchReply
}
