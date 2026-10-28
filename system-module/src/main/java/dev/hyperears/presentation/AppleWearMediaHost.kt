package dev.hyperears.presentation

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import java.lang.reflect.Method

/** Exact media session operations only: no global key dispatch and no route mutations. */
@SuppressLint("MissingPermission", "PrivateApi")
internal class AppleWearMediaHost(private val context: Context, private val handler: Handler) : AppleWearCoordinator.Host {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private var symbols: Symbols? = null
    private var changed: (() -> Unit)? = null
    private val controllers = linkedMapOf<MediaController, MediaController.Callback>()
    private val selected = linkedMapOf<AppleWearPolicy.Player, Pair<String, MediaController>>()
    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) { notifyChange() }
    }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { notifyChange() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { notifyChange() }
    }
    private val mode = AudioManager.OnModeChangedListener { notifyChange() }
    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener {
        runCatching { refreshControllers(); notifyChange() }.onFailure { stop() }
    }

    override fun start(changed: () -> Unit): Boolean = runCatching {
        requireNotNull(audio); requireNotNull(sessions)
        symbols = Symbols()
        this.changed = changed
        audio.registerAudioPlaybackCallback(playback, handler)
        audio.registerAudioDeviceCallback(devices, handler)
        audio.addOnModeChangedListener({ handler.post(it) }, mode)
        sessions.addOnActiveSessionsChangedListener(sessionListener, null, handler)
        refreshControllers()
        true
    }.getOrElse { stop(); false }

    override fun stop() {
        changed = null
        runCatching { audio?.unregisterAudioPlaybackCallback(playback) }
        runCatching { audio?.unregisterAudioDeviceCallback(devices) }
        runCatching { audio?.removeOnModeChangedListener(mode) }
        runCatching { sessions?.removeOnActiveSessionsChangedListener(sessionListener) }
        controllers.forEach { (controller, callback) -> runCatching { controller.unregisterCallback(callback) } }
        controllers.clear(); selected.clear(); symbols = null
    }

    private fun notifyChange() { changed?.invoke() }

    private fun refreshControllers() {
        controllers.forEach { (controller, callback) -> runCatching { controller.unregisterCallback(callback) } }
        controllers.clear()
        sessions?.getActiveSessions(null)?.forEach { controller ->
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) { notifyChange() }
                override fun onAudioInfoChanged(info: MediaController.PlaybackInfo) { notifyChange() }
                override fun onSessionDestroyed() {
                    controllers.remove(controller)?.let { runCatching { controller.unregisterCallback(it) } }
                    notifyChange()
                }
            }
            controller.registerCallback(callback, handler)
            controllers[controller] = callback
        }
    }

    override fun media(address: String): AppleWearCoordinator.Media? = runCatching {
        selected.entries.removeAll { it.value.first == address }
        val manager = audio ?: return null
        val api = symbols ?: return null
        if (manager.mode != AudioManager.MODE_NORMAL) return null
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val route = api.route.invoke(manager, attributes) as? List<*> ?: return null
        if (!matches(route, address)) return null
        val matches = manager.activePlaybackConfigurations.mapNotNull { config ->
            if (config.audioAttributes.usage != AudioAttributes.USAGE_MEDIA) return@mapNotNull null
            val outputs = api.devices.invoke(config) as? List<*> ?: return@mapNotNull null
            val ids = api.deviceIds.get(config) as? IntArray ?: return@mapNotNull null
            if (!matches(outputs, address) || outputs.filterIsInstance<AudioDeviceInfo>().map { it.id }.toSet() != ids.toSet()) return@mapNotNull null
            val uid = api.uid.invoke(config) as? Int ?: return@mapNotNull null
            val id = api.identity.invoke(config) as? Int ?: return@mapNotNull null
            if (uid < 0 || id < 0) return@mapNotNull null
            val candidates = controllers.keys.filter { controller ->
                controller.playbackInfo.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL &&
                    context.packageManager.getPackageUid(controller.packageName, 0) == uid
            }
            val controller = candidates.singleOrNull() ?: return@mapNotNull null
            val state = controller.playbackState ?: return@mapNotNull null
            val playing = state.state == PlaybackState.STATE_PLAYING
            if ((!playing && state.state != PlaybackState.STATE_PAUSED) ||
                api.active.invoke(config) != playing) return@mapNotNull null
            val action = if (playing) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY
            if (state.actions and action == 0L) return@mapNotNull null
            val player = AppleWearPolicy.Player(controller.sessionToken, uid, id)
            Triple(player, controller, playing)
        }
        // Ambiguous simultaneous players/sessions are deliberately inert.
        val match = matches.singleOrNull() ?: return null
        selected[match.first] = address to match.second
        AppleWearCoordinator.Media(match.first, match.third)
    }.getOrNull()

    override fun execute(player: AppleWearPolicy.Player, action: AppleWearPolicy.Action): Boolean = runCatching {
        val (address, controller) = selected[player] ?: return false
        val current = media(address) ?: return false
        if (current.player != player || current.playing != (action == AppleWearPolicy.Action.PAUSE)) return false
        if (action == AppleWearPolicy.Action.PAUSE) controller.transportControls.pause()
        else controller.transportControls.play()
        true
    }.getOrDefault(false)

    private fun matches(devices: List<*>, address: String): Boolean = devices.isNotEmpty() && devices.all {
        it is AudioDeviceInfo && it.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET) &&
            it.address.equals(address, ignoreCase = true)
    }

    private class Symbols {
        private val config = AudioPlaybackConfiguration::class.java
        val active = exact(config, "isActive", Boolean::class.javaPrimitiveType!!)
        val uid = exact(config, "getClientUid", Int::class.javaPrimitiveType!!)
        val identity = exact(config, "getPlayerInterfaceId", Int::class.javaPrimitiveType!!)
        val devices = exact(config, "getAudioDeviceInfos", List::class.java)
        val deviceIds = config.getDeclaredField("mDeviceIds").apply {
            require(type == IntArray::class.java); isAccessible = true
        }
        val route = exact(AudioManager::class.java, "getAudioDevicesForAttributes", List::class.java, AudioAttributes::class.java)
        private fun exact(owner: Class<*>, name: String, result: Class<*>, vararg args: Class<*>): Method =
            owner.getMethod(name, *args).also { require(it.returnType == result) }
    }
}
