package dev.hyperears.presentation

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.VolumeShaper

/** Hidden framework calls are resolved together; a partial API never changes playback. */
@SuppressLint("PrivateApi") // Scoped Xiaomi system host; construction fails inert if symbols are absent.
internal class AppleConversationAudio(private val audio: AudioManager) {
    data class Player(val id: Int, val uid: Int, val proxy: Any)
    private val config = AudioPlaybackConfiguration::class.java
    private val active = config.getMethod("isActive")
    private val identity = config.getMethod("getPlayerInterfaceId")
    private val uid = config.getMethod("getClientUid")
    private val devices = config.getMethod("getAudioDeviceInfos")
    private val deviceIds = config.getDeclaredField("mDeviceIds").apply { isAccessible = true }
    private val proxy = config.getMethod("getPlayerProxy")
    private val apply = Class.forName("android.media.PlayerProxy").getMethod("applyVolumeShaper",
        VolumeShaper.Configuration::class.java, VolumeShaper.Operation::class.java)
    private val setId = VolumeShaper.Configuration.Builder::class.java.getMethod("setId", Int::class.javaPrimitiveType)
    private val options = VolumeShaper.Configuration.Builder::class.java.getMethod("setOptionFlags", Int::class.javaPrimitiveType)
    private val idConfig = VolumeShaper.Configuration::class.java.getConstructor(Int::class.javaPrimitiveType)
    private val operation = Class.forName("android.media.VolumeShaper\$Operation\$Builder")
    private val createOperation = operation.getConstructor()
    private val copyOperation = operation.getConstructor(VolumeShaper.Operation::class.java)
    private val replace = operation.getMethod("replace", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
    private val terminate = operation.getMethod("terminate")
    private val buildOperation = operation.getMethod("build")

    fun players(address: String): List<Player> = audio.activePlaybackConfigurations.mapNotNull { playback ->
        if (active.invoke(playback) != true || playback.audioAttributes.usage != AudioAttributes.USAGE_MEDIA) return@mapNotNull null
        val outputs = devices.invoke(playback) as? List<*> ?: return@mapNotNull null
        val ids = deviceIds.get(playback) as? IntArray ?: return@mapNotNull null
        val resolved = outputs.filterIsInstance<AudioDeviceInfo>()
        // A partially resolved/mirrored route must never duck an unrelated output.
        if (ids.isEmpty() || resolved.map { it.id }.toSet() != ids.toSet() || resolved.any {
                it.type !in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET) ||
                    !it.address.equals(address, ignoreCase = true)
            }) return@mapNotNull null
        val client = uid.invoke(playback) as Int
        if (client < 0) return@mapNotNull null
        Player(identity.invoke(playback) as Int, client, proxy.invoke(playback) ?: return@mapNotNull null)
    }

    fun duck(player: Player, remaining: Long) {
        val duration = remaining.coerceAtLeast(1)
        val attack = minOf(200L, duration / 3).toFloat() / duration
        val release = 1f - minOf(300L, duration / 3).toFloat() / duration
        apply.invoke(player.proxy, envelope(duration, floatArrayOf(0f, attack, release, 1f),
            floatArrayOf(1f, .25f, .25f, 1f)), VolumeShaper.Operation.PLAY)
    }

    fun release(player: Player) {
        val builder = copyOperation.newInstance(VolumeShaper.Operation.PLAY)
        replace.invoke(builder, SHAPER_ID, true)
        apply.invoke(player.proxy, envelope(300, floatArrayOf(0f, 1f), floatArrayOf(.25f, 1f)),
            buildOperation.invoke(builder))
    }

    fun terminate(player: Player) {
        val builder = createOperation.newInstance()
        terminate.invoke(builder)
        apply.invoke(player.proxy, idConfig.newInstance(SHAPER_ID), buildOperation.invoke(builder))
    }

    private fun envelope(duration: Long, times: FloatArray, gains: FloatArray): VolumeShaper.Configuration {
        val builder = VolumeShaper.Configuration.Builder()
        setId.invoke(builder, SHAPER_ID)
        options.invoke(builder, 2) // Framework clock-time envelope survives producer/process loss.
        return builder.setDuration(duration).setInterpolatorType(VolumeShaper.Configuration.INTERPOLATOR_TYPE_LINEAR)
            .setCurve(times, gains).build()
    }

    companion object { private const val SHAPER_ID = 0x48454341 }
}
