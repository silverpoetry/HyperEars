package dev.hyperears.presentation

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.SystemClock
import kotlin.math.roundToInt

/** Existing native headset process only. Reads the actual media route; never guesses by device name. */
@SuppressLint("PrivateApi")
internal class AndroidConnectionVolumeHost(private val context: Context, private val handler: Handler) : ConnectionVolumeHost {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
    private val route = runCatching {
        AudioManager::class.java.getMethod("getAudioDevicesForAttributes", AudioAttributes::class.java)
            .also { check(it.returnType == List::class.java) }
    }.getOrNull()
    private var changed: (() -> Unit)? = null
    private var receiverRegistered = false
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { changed?.invoke() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { changed?.invoke() }
    }
    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) { changed?.invoke() }
    }
    private val mode = AudioManager.OnModeChangedListener { changed?.invoke() }
    private val volume = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { changed?.invoke() }
    }
    override fun now() = SystemClock.elapsedRealtime()
    override fun currentVolume(): Int? = runCatching { audio?.takeUnless { it.isVolumeFixed }?.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
    override fun normalMode(): Boolean = audio?.mode == AudioManager.MODE_NORMAL
    override fun start(changed: () -> Unit): Boolean = runCatching {
        requireNotNull(audio); requireNotNull(route)
        this.changed = changed
        audio.registerAudioDeviceCallback(devices, handler)
        audio.registerAudioPlaybackCallback(playback, handler)
        audio.addOnModeChangedListener({ handler.post(it) }, mode)
        context.registerReceiver(volume, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), null, handler, Context.RECEIVER_EXPORTED)
        receiverRegistered = true
        true
    }.getOrElse { stop(); false }
    override fun stop() {
        changed = null
        runCatching { audio?.unregisterAudioDeviceCallback(devices) }
        runCatching { audio?.unregisterAudioPlaybackCallback(playback) }
        runCatching { audio?.removeOnModeChangedListener(mode) }
        if (receiverRegistered) { receiverRegistered = false; runCatching { context.unregisterReceiver(volume) } }
    }
    override fun routeMatches(address: String): Boolean = runCatching {
        val outputs = route?.invoke(audio, attributes) as? List<*> ?: return false
        exactRoute(outputs, address)
    }.getOrDefault(false)
    override fun apply(address: String, percent: Int, expectedVolume: Int, valid: () -> Boolean): Boolean = runCatching {
        val manager = audio ?: return false
        if (!valid() || !normalMode() || currentVolume() != expectedVolume || !routeMatches(address)) return false
        val target = volumeIndex(percent, manager.getStreamMinVolume(AudioManager.STREAM_MUSIC), manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) ?: return false
        // Re-read owner/settings and route immediately before the one mutation.
        if (!valid() || !normalMode() || currentVolume() != expectedVolume || !routeMatches(address)) return false
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        true
    }.getOrDefault(false)
    override fun schedule(task: Runnable, delayMillis: Long) { handler.postDelayed(task, delayMillis) }
    override fun cancel(task: Runnable) { handler.removeCallbacks(task) }
    companion object {
        internal fun exactRoute(outputs: List<*>, address: String): Boolean = outputs.isNotEmpty() && outputs.all {
            it is AudioDeviceInfo && it.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET) &&
                it.address.equals(address, ignoreCase = true)
        }
        internal fun volumeIndex(percent: Int, minimum: Int, maximum: Int): Int? =
            if (percent !in 0..100 || minimum < 0 || maximum <= 0 || minimum > maximum) null
            else (maximum * (percent / 100.0)).roundToInt().coerceIn(minimum, maximum)
    }
}
