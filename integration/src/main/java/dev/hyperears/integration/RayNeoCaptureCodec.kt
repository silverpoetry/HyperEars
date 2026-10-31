package dev.hyperears.integration

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.*

@Serializable enum class RayNeoRecordingState { COUNTDOWN, RECORDING, STOPPED }

@Serializable @SerialName("rayneo.capture")
data class RayNeoCaptureFeatureState(
    val shotResult: Int? = null,
    val recording: RayNeoRecordingState? = null,
    val recordResult: Int? = null,
) : DeviceFeatureState {
    override val featureId: String get() = FEATURE_ID
    companion object { const val FEATURE_ID = "rayneo.capture" }
}

object RayNeoCaptureCodec {
    const val SHOT_COMMIT_QUERY = "rayneo.capture.commit_shot"
    fun commitShot(): String = buildJsonObject {
        put("uri", "ble.response.mix_shot")
        put("id", UUID.randomUUID().toString())
        put("type", 10)
    }.toString()
    data class Reply(val shot: Boolean, val type: Int)
    fun parse(message: String): Reply? = runCatching {
        val root = Json.parseToJsonElement(message).jsonObject
        val shot = when(root["uri"]?.jsonPrimitive?.content) {
            "ble.request.mix_shot" -> true
            "ble.request.mix_record" -> false
            else -> return null
        }
        val value = root.getValue("type").jsonPrimitive
        require(!value.isString)
        val type = value.int
        require(if(shot) type == 0 || type in 100..106 else type in 0..3 || type in 100..106 || type == 999)
        Reply(shot,type)
    }.getOrNull()
    fun command(shot: Boolean, start: Boolean = true): String = buildJsonObject {
        put("uri",if(shot) "ble.response.mix_shot" else "ble.response.mix_record")
        put("id",UUID.randomUUID().toString())
        put("type",if(shot || start) 0 else 1)
    }.toString()
}
