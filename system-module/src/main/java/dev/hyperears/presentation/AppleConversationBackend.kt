package dev.hyperears.presentation

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.SystemClock

/** Runtime seam: scheduling and actual host operations remain outside episode policy. */
internal interface AppleConversationBackend {
    val available: Boolean
    val normalMode: Boolean
    fun now(): Long
    fun players(address: String): List<AppleConversationAudio.Player>
    fun duck(player: AppleConversationAudio.Player, remaining: Long)
    fun release(player: AppleConversationAudio.Player)
    fun terminate(player: AppleConversationAudio.Player)
    fun schedule(task: Runnable, delayMs: Long)
    fun cancel(task: Runnable)
    fun listen(changed: () -> Unit)
    fun stopListening()
}

internal class AndroidAppleConversationBackend(context: Context, private val handler: Handler) : AppleConversationBackend {
    private val audio = requireNotNull(context.getSystemService(AudioManager::class.java))
    private val shaping = runCatching { AppleConversationAudio(audio) }.getOrNull()
    override val available get() = shaping != null
    override val normalMode get() = audio.mode == AudioManager.MODE_NORMAL
    private var changed: (() -> Unit)? = null
    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) { changed?.invoke() }
    }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { changed?.invoke() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { changed?.invoke() }
    }
    private val mode = AudioManager.OnModeChangedListener { changed?.invoke() }
    override fun now() = SystemClock.elapsedRealtime()
    override fun players(address: String) = shaping?.players(address).orEmpty()
    override fun duck(player: AppleConversationAudio.Player, remaining: Long) { requireNotNull(shaping).duck(player, remaining) }
    override fun release(player: AppleConversationAudio.Player) { requireNotNull(shaping).release(player) }
    override fun terminate(player: AppleConversationAudio.Player) { requireNotNull(shaping).terminate(player) }
    override fun schedule(task: Runnable, delayMs: Long) { handler.postDelayed(task, delayMs) }
    override fun cancel(task: Runnable) { handler.removeCallbacks(task) }
    override fun listen(changed: () -> Unit) {
        this.changed = changed
        audio.registerAudioPlaybackCallback(playback, handler)
        audio.registerAudioDeviceCallback(devices, handler)
        audio.addOnModeChangedListener({ handler.post(it) }, mode)
    }
    override fun stopListening() {
        changed = null
        runCatching { audio.unregisterAudioPlaybackCallback(playback) }
        runCatching { audio.unregisterAudioDeviceCallback(devices) }
        runCatching { audio.removeOnModeChangedListener(mode) }
    }
}
