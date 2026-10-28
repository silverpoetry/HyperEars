package dev.hyperears.presentation

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings

/** Selected Android assistant only. No global media-key dispatch or vendor package fallback. */
@SuppressLint("MissingPermission", "PrivateApi")
internal class AndroidAppleLongPressHost(private val context: Context, private val handler: Handler) : AppleLongPressRuntime.Host {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var changed: (() -> Unit)? = null
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { changed?.invoke() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { changed?.invoke() }
    }
    private val mode = AudioManager.OnModeChangedListener { changed?.invoke() }
    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>?) { changed?.invoke() }
    }
    private val assistantSetting = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { changed?.invoke() }
    }
    override fun now(): Long = SystemClock.elapsedRealtime()
    override fun schedule(task: Runnable, delay: Long) { handler.postDelayed(task, delay) }
    override fun cancel(task: Runnable) { handler.removeCallbacks(task) }

    override fun ready(address: String): Boolean = runCatching {
        val manager = audio ?: return false
        if (manager.mode != AudioManager.MODE_NORMAL || assistantIntent() == null) return false
        val method = AudioManager::class.java.getMethod("getAudioDevicesForAttributes", AudioAttributes::class.java)
        if (method.returnType != List::class.java) return false
        val outputs = method.invoke(manager, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()) as? List<*>
            ?: return false
        outputs.isNotEmpty() && outputs.all {
            it is AudioDeviceInfo && it.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET) &&
                it.address.equals(address, ignoreCase = true)
        }
    }.getOrDefault(false)

    private fun assistantIntent(): Intent? {
        val selected = Settings.Secure.getString(context.contentResolver, "assistant")
            ?.let(ComponentName::unflattenFromString) ?: return null
        return listOf(Intent.ACTION_VOICE_COMMAND, Intent.ACTION_ASSIST).firstNotNullOfOrNull { action ->
            val intent = Intent(action).setPackage(selected.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolved = intent.resolveActivity(context.packageManager)
            if (resolved?.packageName == selected.packageName) intent.setComponent(resolved) else null
        }
    }

    override fun assistant(address: String): Boolean = runCatching {
        if (!ready(address)) return false
        context.startActivity(assistantIntent() ?: return false)
        true
    }.getOrDefault(false)

    override fun listen(changed: () -> Unit): Boolean = runCatching {
        val manager = requireNotNull(audio)
        this.changed = changed
        manager.registerAudioDeviceCallback(devices, handler)
        manager.registerAudioPlaybackCallback(playback, handler)
        manager.addOnModeChangedListener({ handler.post(it) }, mode)
        context.contentResolver.registerContentObserver(Settings.Secure.getUriFor("assistant"), false, assistantSetting)
        true
    }.getOrElse { stop(); false }

    override fun stop() {
        changed = null
        runCatching { audio?.unregisterAudioDeviceCallback(devices) }
        runCatching { audio?.unregisterAudioPlaybackCallback(playback) }
        runCatching { audio?.removeOnModeChangedListener(mode) }
        runCatching { context.contentResolver.unregisterContentObserver(assistantSetting) }
    }
}
