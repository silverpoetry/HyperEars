package dev.hyperears.presentation

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import dev.hyperears.hook.ModuleLog
import dev.hyperears.hook.maskBluetoothAddress
import dev.hyperears.integration.BatteryReading

// Drawn-battery colors live at file top level: object-level non-const vals would drag the whole
// object (main-looper handler) into JVM unit-test class initialization.
internal val COLOR_ON_TRANSPARENT = 0xFFFFFFFF.toInt()
internal val COLOR_BATTERY_LOW = 0xFFE5392B.toInt()
internal val COLOR_BATTERY_NORMAL = 0xFF2196F3.toInt()

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
    const val FOCUS_PICS_KEY = "miui.focus.pics"
    const val FOCUS_ACTIONS_KEY = "miui.focus.actions"
    internal const val FOCUS_PIC_EARPHONE = "miui.focus.pic_earphone"
    internal const val FOCUS_PIC_CONNECT = "miui.focus.pic_connect_button"
    // Own battery-artwork keys inside the host's pics bundle (custom names are host-legal: SystemUI
    // resolves them only through the extras bundle; the JSON side stays the §11 L230-233 picInfo form).
    // The expanded island has one key only: the host ignores imageTextInfoRight (device 2026-10-31:
    // the symmetric right slot we mounted was never consumed), so both ears share one strip image.
    internal const val FOCUS_PIC_BATTERY_LEFT = "hyperears_battery_left"
    internal const val FOCUS_PIC_BATTERY_COMBINED = "hyperears_battery_combined"
    internal const val FOCUS_BUSINESS = "btheadsetnotification"
    private const val FOCUS_ACTION_TEST_KEY = "miui.focus.action_test"
    // Host plugin resource ids for the visual fields (island-spec §11: smallIcon 0x7f0800af at
    // MBN:6387, button pic 0x7f0805d1 at :6293, strings 0x7f1200ef at :6215 and 0x7f12023d at :822).
    private const val RES_SMALL_ICON = 0x7f0800af
    private const val RES_CONNECT_BUTTON = 0x7f0805d1
    private const val RES_CONNECTED_TEXT = 0x7f1200ef
    private const val RES_ACTION_TITLE = 0x7f12023d
    // Reusing the host's own autoswitch broadcast keeps the action inert for a connected device and
    // introduces no new receiver component (island-spec §11 action evidence :6125-6160).
    private const val AUTOSWITCH_ACTION = "com.xiaomi.bluetooth.headset.autoswitch.connect"
    private const val HOST_PACKAGE = "com.xiaomi.bluetooth"
    private const val EXTRA_ADDRESS = "EXTRA_ADDRESS"
    internal const val BATTERY_PIC_SIZE = 96
    // The expanded strip is two 96 px cells side by side; each glyph keeps 6 px to its cell edge, so
    // the shells stand 14 px apart and the 8 px gutter requirement holds without rescaling the glyph.
    internal const val COMBINED_PIC_WIDTH = BATTERY_PIC_SIZE * 2
    internal const val LOW_BATTERY_PERCENT = 20
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
        // Host semantics (island-spec §11): device name is the ticker; the text slots carry the
        // battery (user choice — island and popup never share the screen), falling back to the
        // plugin status string only when no ear battery exists.
        val statusText = hostString(context, RES_CONNECTED_TEXT, "已连接")
        val actionTitle = hostString(context, RES_ACTION_TITLE, "连接")
        val actionIntent = Intent(AUTOSWITCH_ACTION).setPackage(HOST_PACKAGE)
            .putExtra(EXTRA_ADDRESS, projection.address)
        // Artwork first: the JSON pic references are only emitted for battery icons that actually got
        // drawn, so a drawing failure degrades to the earphone key / text-only content and never
        // leaves the param pointing at pics that do not exist.
        val layout = batteryArtworkLayout(projection.left, projection.right)
        // Collapsed state keeps a single left-ear glyph (the folded island has no room for two), the
        // expanded state carries the combined strip in the host's only consumed pic slot.
        val leftBatteryIcon = if (projection.left.available) batteryIcon(projection.left.percent) else null
        val combinedIcon = combinedBatteryIcon(layout, projection.left.percent, projection.right.percent)
        val param = focusParamJson(FocusIslandText(
            title = projection.name,
            content = focusIslandContent(projection.left, projection.right, statusText),
            actionTitle = actionTitle, actionIntentUri = actionIntent.toUri(0),
            bigPic = islandBigPicKey(layout, combinedIcon != null),
            smallPic = islandSmallPicKey(projection.left, leftBatteryIcon != null),
        ))
        val manager = context.getSystemService(NotificationManager::class.java) ?: run {
            ModuleLog.debug(TAG, "notification not submitted: notification manager absent")
            return false
        }
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, CHANNEL_ID, NotificationManager.IMPORTANCE_DEFAULT,
        ))
        val extras = Bundle()
        extras.putString(FOCUS_PARAM_KEY, param)
        picsBundle(context, leftBatteryIcon, combinedIcon)?.let { extras.putBundle(FOCUS_PICS_KEY, it) }
        actionsBundle(context, actionTitle, actionIntent)?.let { extras.putBundle(FOCUS_ACTIONS_KEY, it) }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIconRes(context))
            .addExtras(extras)
            .build()
        manager.notify(CHANNEL_ID, NOTIFY_ID, notification)
        ModuleLog.debug(TAG, "notification submitted id=$NOTIFY_ID islandType=param_v2 " +
            "channel=$CHANNEL_ID extras=${extras.keySet().size} " +
            "address=${maskBluetoothAddress(projection.address)}")
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

    private fun hostString(context: Context, resId: Int, fallback: String): String =
        runCatching { context.getString(resId) }.getOrDefault(fallback)

    /** Never throws: a plugin resource id that this context cannot resolve falls back to the safe icon. */
    private fun hostIcon(context: Context, resId: Int): Icon =
        runCatching { Icon.createWithResource(context, resId) }.getOrDefault(
            Icon.createWithResource(context, android.R.drawable.stat_sys_data_bluetooth),
        )

    private fun smallIconRes(context: Context): Int =
        if (runCatching { context.resources.getResourceEntryName(RES_SMALL_ICON) }.isSuccess) {
            RES_SMALL_ICON
        } else android.R.drawable.stat_sys_data_bluetooth

    private fun picsBundle(context: Context, leftIcon: Icon?, combinedIcon: Icon?): Bundle? = runCatching {
        Bundle().apply {
            // The host stores a model-specific artwork under pic_earphone (getHeadsetPic); we keep
            // the generic icon there and add our drawn battery artwork under own keys (§11 L224).
            putParcelable(FOCUS_PIC_EARPHONE, hostIcon(context, RES_SMALL_ICON))
            putParcelable(FOCUS_PIC_CONNECT, hostIcon(context, RES_CONNECT_BUTTON))
            leftIcon?.let { putParcelable(FOCUS_PIC_BATTERY_LEFT, it) }
            combinedIcon?.let { putParcelable(FOCUS_PIC_BATTERY_COMBINED, it) }
        }
    }.getOrNull()

    /**
     * A drawn system-style battery for the collapsed slot: rounded shell and terminal on transparent
     * background, proportional fill tinted by the §4 low-battery threshold, and the percent label
     * below. 96x96 matches the square picInfo artwork slots; any drawing failure yields null and the
     * text content remains the fallback — the notification submission never depends on this path.
     */
    private fun batteryIcon(percent: Int?): Icon? = runCatching {
        val bitmap = createBitmap(BATTERY_PIC_SIZE, BATTERY_PIC_SIZE, Bitmap.Config.ARGB_8888)
        drawBatteryGlyph(Canvas(bitmap), Paint(Paint.ANTI_ALIAS_FLAG), percent, 0f)
        Icon.createWithBitmap(bitmap)
    }.getOrNull()

    /**
     * The expanded-slot strip: both ears share one image because the host consumes imageTextInfoLeft
     * only. Two readings occupy one 96 px cell each inside the 192x96 transparent strip; a lone
     * reading is drawn alone in the plain square (centred by being the only cell); no reading draws
     * nothing, leaving the host earphone picture as the caller's fallback.
     */
    private fun combinedBatteryIcon(
        layout: BatteryArtworkLayout, leftPercent: Int?, rightPercent: Int?,
    ): Icon? {
        if (layout == BatteryArtworkLayout.NONE) return null
        return runCatching {
            val both = layout == BatteryArtworkLayout.BOTH
            val width = if (both) COMBINED_PIC_WIDTH else BATTERY_PIC_SIZE
            val bitmap = createBitmap(width, BATTERY_PIC_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            when (layout) {
                BatteryArtworkLayout.BOTH -> {
                    drawBatteryGlyph(canvas, paint, leftPercent, 0f)
                    drawBatteryGlyph(canvas, paint, rightPercent, BATTERY_PIC_SIZE.toFloat())
                }
                BatteryArtworkLayout.LEFT_ONLY -> drawBatteryGlyph(canvas, paint, leftPercent, 0f)
                BatteryArtworkLayout.RIGHT_ONLY -> drawBatteryGlyph(canvas, paint, rightPercent, 0f)
                // Unreachable: NONE returned above, listed so the when stays exhaustive.
                BatteryArtworkLayout.NONE -> Unit
            }
            Icon.createWithBitmap(bitmap)
        }.getOrNull()
    }

    /** One battery glyph inside a 96 px cell whose left edge sits at [offsetX]. */
    private fun drawBatteryGlyph(canvas: Canvas, paint: Paint, percent: Int?, offsetX: Float) {
        val ratio = batteryFillRatio(percent)
        // KTX save/restore pair: a bare save()/restore() trips lint's UseKtx.
        canvas.withTranslation(offsetX, 0f) {
            paint.color = COLOR_ON_TRANSPARENT
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 5f
            drawRoundRect(8f, 30f, 80f, 66f, 10f, 10f, paint)
            paint.style = Paint.Style.FILL
            drawRect(84f, 42f, 90f, 54f, paint)
            paint.color = batteryFillColorRgb(percent)
            if (ratio > 0f) {
                drawRoundRect(14f, 36f, 14f + 60f * ratio, 60f, 6f, 6f, paint)
            }
            paint.color = COLOR_ON_TRANSPARENT
            paint.textSize = 22f
            paint.textAlign = Paint.Align.CENTER
            drawText(batteryLabel(percent), BATTERY_PIC_SIZE / 2f, 92f, paint)
        }
    }

    private fun actionsBundle(context: Context, actionTitle: String, actionIntent: Intent): Bundle? =
        runCatching {
            val pending = PendingIntent.getBroadcast(
                context, 0, actionIntent, PendingIntent.FLAG_IMMUTABLE,
            )
            Bundle().apply {
                putParcelable(FOCUS_ACTION_TEST_KEY, Notification.Action.Builder(
                    hostIcon(context, RES_SMALL_ICON), actionTitle, pending,
                ).build())
            }
        }.getOrNull()
}

/**
 * The text fields of the host param_v2 body (island-spec §11 L229-L238): the device name is the
 * `ticker` and the `iconTextInfo.title`; the content carries the battery text (user choice: the
 * island and the popup are mutually exclusive, so the battery belongs in the island too) and falls
 * back to the host status string only when no battery reading is available. `bigPic` feeds the
 * expanded slot (the combined strip), `smallPic` the collapsed slot and the icon animation source.
 */
internal data class FocusIslandText(val title: String, val content: String,
    val actionTitle: String, val actionIntentUri: String,
    val bigPic: String, val smallPic: String)

/** `L 85% · R 90%` / a single side without placeholder when the other is missing / status fallback. */
internal fun focusIslandContent(left: BatteryReading, right: BatteryReading, fallback: String): String =
    listOfNotNull(islandBatteryText(left)?.let { "L $it" }, islandBatteryText(right)?.let { "R $it" })
        .joinToString(" · ").ifEmpty { fallback }

/** Which ears the expanded-slot artwork has to carry; [NONE] leaves the host earphone picture. */
internal enum class BatteryArtworkLayout { BOTH, LEFT_ONLY, RIGHT_ONLY, NONE }

internal fun batteryArtworkLayout(left: BatteryReading, right: BatteryReading): BatteryArtworkLayout =
    when {
        left.available && right.available -> BatteryArtworkLayout.BOTH
        left.available -> BatteryArtworkLayout.LEFT_ONLY
        right.available -> BatteryArtworkLayout.RIGHT_ONLY
        else -> BatteryArtworkLayout.NONE
    }

/**
 * Collapsed slot: one left-ear glyph only — the folded island is too small for a second battery, so
 * the generic earphone picture stands in whenever a left reading or the drawing is missing.
 */
internal fun islandSmallPicKey(left: BatteryReading, artworkAvailable: Boolean): String =
    if (left.available && artworkAvailable) NativeIslandNotification.FOCUS_PIC_BATTERY_LEFT
    else NativeIslandNotification.FOCUS_PIC_EARPHONE

/**
 * Expanded slot (island-spec §11 L230-233): the host consumes `imageTextInfoLeft` only (device
 * 2026-10-31: a symmetric `imageTextInfoRight` was ignored), so its picInfo points at the combined
 * strip as soon as any ear produced artwork; otherwise the generic earphone picture keeps the shape.
 */
internal fun islandBigPicKey(layout: BatteryArtworkLayout, artworkAvailable: Boolean): String =
    if (layout != BatteryArtworkLayout.NONE && artworkAvailable)
        NativeIslandNotification.FOCUS_PIC_BATTERY_COMBINED
    else NativeIslandNotification.FOCUS_PIC_EARPHONE

/** Fill fraction for the drawn battery: null percent draws empty, values clamp to 0..1. */
internal fun batteryFillRatio(percent: Int?): Float =
    ((percent ?: 0).coerceIn(0, 100)) / 100f

/** The label under the battery shell; "-" marks a missing reading like the host's state source. */
internal fun batteryLabel(percent: Int?): String = percent?.let { "$it%" } ?: "-"

/** Low battery reuses the §4 twenty-percent threshold; colors are explicit ARGB (bitmap-drawn). */
internal fun batteryFillColorRgb(percent: Int?): Int =
    if (percent != null && percent <= NativeIslandNotification.LOW_BATTERY_PERCENT)
        COLOR_BATTERY_LOW else COLOR_BATTERY_NORMAL

/** The focus extras key table (island-spec §11 L220-L225); pics/actions may degrade to absent. */
internal fun focusExtrasKeys(): List<String> = listOf(
    NativeIslandNotification.FOCUS_PARAM_KEY,
    NativeIslandNotification.FOCUS_PICS_KEY,
    NativeIslandNotification.FOCUS_ACTIONS_KEY,
)

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
    val picInfo = { pic: String -> jsonObject("type" to "1", "pic" to jsonString(pic)) }
    val island = jsonObject(
        "islandProperty" to "1",
        "islandTimeout" to "10",
        "bigIslandArea" to jsonObject(
            "imageTextInfoLeft" to jsonObject("type" to "1", "picInfo" to picInfo(text.bigPic)),
            "textInfo" to jsonObject("title" to jsonString(text.content)),
        ),
        "smallIslandArea" to jsonObject("picInfo" to picInfo(text.smallPic)),
    )
    val paramV2 = jsonObject(
        "protocol" to "1",
        "business" to jsonString(NativeIslandNotification.FOCUS_BUSINESS),
        "updatable" to "true",
        "enableFloat" to "true",
        "timeout" to "1",
        "ticker" to jsonString(text.title),
        "tickerPic" to jsonString(NativeIslandNotification.FOCUS_PIC_CONNECT),
        "param_island" to island,
        "iconTextInfo" to jsonObject(
            "type" to "1",
            "title" to jsonString(text.title),
            "content" to jsonString(text.content),
            "animIconInfo" to jsonObject("type" to "0", "src" to jsonString(text.smallPic)),
        ),
        "actions" to "[" + jsonObject(
            "type" to "2",
            "actionTitle" to jsonString(text.actionTitle),
            "actionTitleColor" to jsonString("#FF000000"),
            "actionTitleColorDark" to jsonString("#FFFFFF"),
            "actionBgColorDark" to jsonString("#0D84FF"),
            "actionIntentType" to "2",
            "actionIntent" to jsonString(text.actionIntentUri),
        ) + "]",
    )
    return requireNotNull(jsonObject("param_v2" to paramV2))
}
