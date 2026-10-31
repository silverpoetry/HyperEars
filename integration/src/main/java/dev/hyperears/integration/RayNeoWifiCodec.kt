package dev.hyperears.integration

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.*

@Serializable
data class RayNeoWifiNetwork(val ssid: String, val bssid: String?, val capabilities: String,
    val frequency: Int, val level: Int, val saved: Boolean) {
    val selectionKey: String get() = bssid?.let { "bssid:$it" } ?: "ssid:$ssid"
    val requiresPassword: Boolean get() = !saved && capabilities.contains("PSK",true)
    val canConnect: Boolean get() = saved || capabilities.contains("PSK",true) ||
        (capabilities.contains("ESS",true) && listOf("WPA","WEP","EAP","SAE","OWE","WAPI").none { capabilities.contains(it,true) })
    fun acceptsPassword(password: String?) = canConnect && when {
        saved -> password == null
        requiresPassword -> password != null && password.toByteArray(Charsets.UTF_8).size in 8..63
        else -> password == null
    }
}

@Serializable @SerialName("rayneo.wifi.networks")
data class RayNeoWifiFeatureState(val networks: List<RayNeoWifiNetwork> = emptyList(),
    val ssid: String? = null, val connectionState: String? = null) : DeviceFeatureState {
    override val featureId: String get() = FEATURE_ID
    fun supports(request: RayNeoControlRequest.ConnectWifi) = networks.any {
        it == request.network && it.acceptsPassword(request.password)
    }
    companion object {
        const val FEATURE_ID = "rayneo.wifi.networks"
        const val STOP_QUERY = "rayneo.wifi.scan.stop"
    }
}

sealed interface RayNeoWifiReply {
    data class Scan(val networks: List<RayNeoWifiNetwork>) : RayNeoWifiReply
    data class Connection(val ssid: String, val state: String) : RayNeoWifiReply
}

object RayNeoWifiCodec {
    fun parse(message: String): RayNeoWifiReply? = runCatching {
        val root = Json.parseToJsonElement(message).jsonObject
        when(root["uri"]?.jsonPrimitive?.content) {
            "ble.request.scan_wifi_result" -> {
                val array = root.getValue("scanResults").jsonArray
                require(array.size <= 256)
                RayNeoWifiReply.Scan(array.map { entry ->
                    val o = entry.jsonObject
                    fun text(key: String) = o.getValue(key).jsonPrimitive.let { require(it.isString); it.content }
                    fun number(key: String) = o.getValue(key).jsonPrimitive.let { require(!it.isString); it.int }
                    val ssid = text("ssid"); val bssid = o["bssid"]?.takeUnless { it is JsonNull }?.let { text("bssid") }; val caps = text("capabilities")
                    require(ssid.toByteArray(Charsets.UTF_8).size in 1..32)
                    require((bssid == null || Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}").matches(bssid)) && caps.length <= 512)
                    val saved = o.getValue("saved").jsonPrimitive.let { require(!it.isString); it.boolean }
                    RayNeoWifiNetwork(ssid,bssid,caps,number("frequency"),number("level"),saved)
                }.distinctBy { it.selectionKey })
            }
            "ble.request.wifi.connect_state" -> {
                val ssid = root.getValue("ssid").jsonPrimitive.content
                val state = root.getValue("state").jsonPrimitive.content
                require(state in setOf("CONNECTED","CONNECTING","DISCONNECTED","DISCONNECTING","SUSPENDED","UNKNOWN"))
                require(ssid.length <= 128)
                RayNeoWifiReply.Connection(ssid,state)
            }
            else -> null
        }
    }.getOrNull()

    fun scan(start: Boolean, countryCode: String): String {
        require(countryCode.isEmpty() || Regex("[A-Z]{2}").matches(countryCode))
        return buildJsonObject {
            put("uri","ble.response.wifi.scan"); put("id",UUID.randomUUID().toString())
            put("start",start); put("countryCode",countryCode)
        }.toString()
    }
    fun connect(request: RayNeoControlRequest.ConnectWifi): String {
        require(request.network.acceptsPassword(request.password))
        return buildJsonObject {
            put("uri","ble.response.wifi.connect"); put("id",UUID.randomUUID().toString())
            put("wifi",JsonObject(Json.encodeToJsonElement(request.network).jsonObject.filterValues { it !is JsonNull }))
            request.password?.let { put("password",it) }
        }.toString()
    }
}
