package dev.hyperears.presentation

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import dev.hyperears.hook.ModuleLog
import dev.hyperears.hook.maskBluetoothAddress

/**
 * The OS3 focus-notification island route (island-spec §11). The direct status-bar `setStatus`
 * channel is silently dropped by SystemUI on this generation (device 2026-10-31 00:47: our action
 * never reached `CommandQueueExt setStatus` while a third-party action did), so the island is
 * submitted as the host's own focus notification instead: channel `HEADSET_CONNECT_NOTIFICATION`,
 * tag/id identical to the host, extras key `miui.focus.param` carrying the param_v2 JSON.
 *
 * This runs inside the host Bluetooth process, whose notification access is a system fixed grant
 * (device audit: POST_NOTIFICATIONS and STATUS_BAR both granted=true), so the module manifest
 * declares no notification permission of its own.
 *
 * Host-state red line: reads only the module projection; the only writes are to the freshly built
 * channel/notification/Bundle and the shared NotificationManager slot of the host's own tag+id.
 */
internal object NativeIslandNotification {
    private const val TAG = "NativeIsland"
    private const val CHANNEL_ID = "HEADSET_CONNECT_NOTIFICATION"
    // Host identity pair (island-spec §11): notify(tag=HEADSET_CONNECT_NOTIFICATION, id=0x3e9).
    const val NOTIFY_ID = 999
    const val CANCEL_DELAY_MS = 5_000L
    const val FOCUS_PARAM_KEY = "miui.focus.param"
    internal const val FOCUS_PIC_EARPHONE = "miui.focus.pic_earphone"
    internal const val FOCUS_PIC_CONNECT = "miui.focus.pic_connect_button"
    internal const val FOCUS_BUSINESS = "btheadsetnotification"
    // Single object-owned main-looper handler: one pending generation at a time, no per-submit
    // handler instances and no runnable retention after the cancel fires or is replaced. Declared as
    // a plain object property, not a companion: this file already crashes lint's FIR analyzer in a
    // companion-inside-object form, and a non-local return inside runCatching lambdas in a second form.
    private val handler = Handler(Looper.getMainLooper())

    /**
     * The v1 shape carries no `actions`/`pics` (no legitimate jump target and no model artwork) and
     * uses a framework small icon, so nothing here depends on plugin resource resolution.
     */
    fun tryShow(context: Context, projection: NativeHeadsetProjection): Boolean = runCatching {
        show(context, projection)
    }.getOrElse { failure ->
        ModuleLog.debug(TAG, "notification not submitted: ${failure.javaClass.name}")
        false
    }

    // The host Bluetooth process posts under the system-fixed grants audited on device (POST_NOTIFICATIONS
    // and STATUS_BAR both granted=true); the module manifest declares neither. NotificationPermission is
    // the API-33 checker for notify(); MissingPermission covers the activeNotifications read.
    @SuppressLint("NotificationPermission", "MissingPermission")
    private fun show(context: Context, projection: NativeHeadsetProjection): Boolean {
        if (!projection.canShowConnectionHint) {
            ModuleLog.debug(TAG, "notification not submitted: usable ear battery unavailable")
            return false
        }
        val battery = listOfNotNull(
            islandBatteryText(projection.left)?.let { "L $it" },
            islandBatteryText(projection.right)?.let { "R $it" },
        ).joinToString(" · ")
        val param = focusParamJson(FocusIslandText(
            ticker = projection.name, title = projection.name, content = battery,
        ))
        val manager = context.getSystemService(NotificationManager::class.java) ?: run {
            ModuleLog.debug(TAG, "notification not submitted: notification manager absent")
            return false
        }
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, CHANNEL_ID, NotificationManager.IMPORTANCE_DEFAULT,
        ))
        val extras = Bundle()
        focusExtrasEntries(param).forEach { extras.putString(it.key, it.value) }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .addExtras(extras)
            .build()
        manager.notify(CHANNEL_ID, NOTIFY_ID, notification)
        ModuleLog.debug(TAG, "notification submitted id=$NOTIFY_ID islandType=param_v2 " +
            "channel=$CHANNEL_ID extras=${focusExtrasEntries(param).size} " +
            "address=${maskBluetoothAddress(projection.address)} " +
            "left=${projection.left.percent} right=${projection.right.percent}")
        // The host leaves the notification resident; a third-party text island must not clutter the
        // shade, so the same tag+id is cancelled once the island window (the 5 s connect duration)
        // has passed. One shared handler owns at most one pending generation: a newer submission
        // removes the older timer, so an old generation never deletes the newer notification. The
        // delayed cancel additionally checks ownership: if the host itself posted the same tag+id in
        // the window (real AirPods path), its extras carry a different param and stay untouched.
        // Residual risk (documented in the changelog): if the host process dies before the timer,
        // the notification can linger until the shade is cleared by hand.
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ cancelIfStillMine(manager, param) }, CANCEL_DELAY_MS)
        return true
    }

    private fun cancelIfStillMine(manager: NotificationManager, param: String) {
        val attempt = runCatching { manager.activeNotifications.toList() }
        if (attempt.isFailure) {
            ModuleLog.debug(TAG, "notification cancel check unavailable: " +
                "${attempt.exceptionOrNull()?.javaClass?.name}; retaining")
            return
        }
        val slot = attempt.getOrThrow().firstOrNull { it.tag == CHANNEL_ID && it.id == NOTIFY_ID }
        if (shouldCancelFocusNotification(slot?.notification?.extras?.getString(FOCUS_PARAM_KEY), param)) {
            manager.cancel(CHANNEL_ID, NOTIFY_ID)
        }
    }
}

/** The three text fields the host fills with device strings (island-spec §11). */
internal data class FocusIslandText(val ticker: String, val title: String, val content: String)

/** The extras key table of the focus notification (v1: only the param JSON, spec §11). */
internal data class FocusExtrasEntry(val key: String, val value: String)

internal fun focusExtrasEntries(paramJson: String): List<FocusExtrasEntry> =
    listOf(FocusExtrasEntry(NativeIslandNotification.FOCUS_PARAM_KEY, paramJson))

/**
 * Cancel ownership (review Minor): the delayed retract may delete the shared tag+id slot only while
 * it still carries this submission's param. A slot gone (null), foreign (the host's own AirPods post)
 * or overwritten by a newer generation is retained — retraction only ever removes our own artifact.
 */
internal fun shouldCancelFocusNotification(activeParam: String?, myParam: String): Boolean =
    activeParam == myParam

/**
 * The param_v2 JSON body (island-spec §11): key order follows the host's org.json put order, the
 * fixed constants stay verbatim, and the text slots take the caller's device/battery strings.
 */
internal fun focusParamJson(text: FocusIslandText): String {
    val earphone = jsonString(NativeIslandNotification.FOCUS_PIC_EARPHONE)
    val picInfo = jsonObject("type" to "1", "pic" to earphone)
    val island = jsonObject(
        "islandProperty" to "1",
        "islandTimeout" to "10",
        "bigIslandArea" to jsonObject(
            "imageTextInfoLeft" to jsonObject("type" to "1", "picInfo" to picInfo),
            "textInfo" to jsonObject("title" to jsonString(text.title)),
        ),
        "smallIslandArea" to jsonObject("picInfo" to picInfo),
    )
    val paramV2 = jsonObject(
        "protocol" to "1",
        "business" to jsonString(NativeIslandNotification.FOCUS_BUSINESS),
        "updatable" to "true",
        "enableFloat" to "true",
        "timeout" to "1",
        "ticker" to jsonString(text.ticker),
        "tickerPic" to jsonString(NativeIslandNotification.FOCUS_PIC_CONNECT),
        "param_island" to island,
        "iconTextInfo" to jsonObject(
            "type" to "1",
            "title" to jsonString(text.title),
            "content" to jsonString(text.content),
            "animIconInfo" to jsonObject("type" to "0", "src" to earphone),
        ),
    )
    return requireNotNull(jsonObject("param_v2" to paramV2))
}
