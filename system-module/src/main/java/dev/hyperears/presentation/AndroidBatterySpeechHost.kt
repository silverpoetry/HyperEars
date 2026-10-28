package dev.hyperears.presentation

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.*
import android.os.Bundle
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.hyperears.settings.BatterySpeechMode
import java.util.Locale

internal fun androidBatterySpeechHost(context: Context, handler: Handler): BatterySpeechHost = BatterySpeechUtterance(
    object : SpeechScheduler {
        override fun now() = SystemClock.elapsedRealtime()
        override fun post(task: Runnable) { handler.post(task) }
        override fun after(task: Runnable, milliseconds: Long) { handler.postDelayed(task, milliseconds) }
        override fun cancel(task: Runnable) { handler.removeCallbacks(task) }
    },
    SpeechEngineFactory { initialized, completed ->
        val tts = TextToSpeech(context) { initialized(it == TextToSpeech.SUCCESS) }
        try {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { utteranceId?.let(completed) }
            @Deprecated("Legacy TTS callback")
            override fun onError(utteranceId: String?) { utteranceId?.let(completed) }
            override fun onError(utteranceId: String?, errorCode: Int) { utteranceId?.let(completed) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { utteranceId?.let(completed) }
        })
        object : SpeechEngine {
            override fun prepare() = tts.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE) >= TextToSpeech.LANG_AVAILABLE &&
                tts.setLanguage(Locale.SIMPLIFIED_CHINESE) >= TextToSpeech.LANG_AVAILABLE &&
                tts.setAudioAttributes(AndroidSpeechAudioGate.speechAttributes()) == TextToSpeech.SUCCESS
            override fun speak(text: String, volume: Float, utteranceId: String) =
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) }, utteranceId) == TextToSpeech.SUCCESS
            override fun stop() { tts.stop() }
            override fun shutdown() { tts.shutdown() }
        }
        } catch (error: Exception) {
            runCatching { tts.stop() }; runCatching { tts.shutdown() }
            throw error
        }
    },
    { AndroidSpeechAudioGate(context, handler) },
)

/** Per-utterance observers; neither an independent service nor a permanent engine. */
@SuppressLint("PrivateApi")
internal class AndroidSpeechAudioGate(private val context: Context, private val handler: Handler) : SpeechAudioGate {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val route = runCatching {
        AudioManager::class.java.getMethod("getAudioDevicesForAttributes", AudioAttributes::class.java)
            .also { check(it.returnType == List::class.java) }
    }.getOrNull()
    private var changed: (() -> Unit)? = null
    private var registered = false
    private var focus: AudioFocusRequest? = null
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { changed?.invoke() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { changed?.invoke() }
    }
    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) { changed?.invoke() }
    }
    private val mode = AudioManager.OnModeChangedListener { changed?.invoke() }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { changed?.invoke() }
    }
    override fun allowed(address: String, mode: BatterySpeechMode): Boolean = runCatching {
        if (mode == BatterySpeechMode.OFF || audio == null || notifications == null || power == null) return false
        if (mode == BatterySpeechMode.SCREEN_OFF && power.isInteractive) return false
        if (audio.mode != AudioManager.MODE_NORMAL || audio.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
            audio.isStreamMute(AudioManager.STREAM_MUSIC) || audio.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0 ||
            notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return false
        listOf(speechAttributes(), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()).all { attrs ->
            val outputs = route?.invoke(audio, attrs) as? List<*> ?: return false
            AndroidConnectionVolumeHost.exactRoute(outputs, address)
        }
    }.getOrDefault(false)
    override fun watch(changed: () -> Unit): Boolean = runCatching {
        requireNotNull(audio); requireNotNull(route)
        this.changed = changed
        audio.registerAudioDeviceCallback(devices, handler)
        audio.registerAudioPlaybackCallback(playback, handler)
        audio.addOnModeChangedListener({ handler.post(it) }, mode)
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            addAction("android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED")
            addAction("android.bluetooth.action.LE_AUDIO_ACTIVE_DEVICE_CHANGED")
        }, null, handler, Context.RECEIVER_EXPORTED)
        registered = true
        true
    }.getOrElse { close(); false }
    override fun requestFocus(lost: () -> Unit): Boolean {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(speechAttributes()).setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ if (it != AudioManager.AUDIOFOCUS_GAIN) lost() }, handler).build()
        focus = request
        return audio?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
    override fun abandonFocus() { focus?.let { audio?.abandonAudioFocusRequest(it) }; focus = null }
    override fun close() {
        changed = null
        runCatching { audio?.unregisterAudioDeviceCallback(devices) }
        runCatching { audio?.unregisterAudioPlaybackCallback(playback) }
        runCatching { audio?.removeOnModeChangedListener(mode) }
        if (registered) { registered = false; runCatching { context.unregisterReceiver(receiver) } }
    }
    companion object {
        fun speechAttributes(): AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    }
}
