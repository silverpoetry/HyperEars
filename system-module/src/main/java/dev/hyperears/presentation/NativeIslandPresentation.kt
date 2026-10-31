package dev.hyperears.presentation

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import dev.hyperears.hook.ModuleLog
import dev.hyperears.hook.maskBluetoothAddress
import dev.hyperears.integration.BatteryReading

/**
 * Third-party text-island renderer for builds whose native island chain is unreachable because the
 * host never received the AirPods identity data (issue #102). It assembles the island payload itself
 * and submits it straight to the system status-bar render endpoint, bypassing only the plugin's own
 * business gates, never the module's ownership or window guards.
 *
 * Coexistence red line: the runtime routes the island target to this renderer directly when the
 * ownership contract is confirmed absent ([OwnershipContractState.MISSING]), because on those builds
 * the native hint reports success even when it silently failed and its verdict carries no proof. On
 * every build that still carries the ownership contract the previous "native hint only" behavior is
 * unchanged and this renderer is never reached.
 *
 * Host-state red line: this object only reads the module's own projection and the system service. It
 * never writes host SharedPreferences, Settings, the file provider, filesDir, or sends host intents;
 * all Bundle writes target the freshly created in-memory Bundle below.
 */
internal object NativeIslandPresentation {
    private const val TAG = "NativeIsland"
    // Public, un-obfuscated payload classes the host build exposes (island-spec §3, §9).
    private const val PARAM_CLASS = "com.android.bluetooth.ble.app.MiuiHeadsetIslandParam"
    // Render endpoint constants (island-spec §1): the int code is hardcoded to 1 in the host and
    // pairs with the action string; SystemUI parses the Bundle contract in island-spec §2.2.
    private const val STATUS_BAR_SERVICE = "statusbar"
    private const val STATUS_CODE = 1
    private const val STRONG_TOAST_ACTION = "strong_toast_action"
    // The connected two-battery island case 0x75 (island-spec §2.1): 0x1388 = 5000 ms.
    private const val CONNECT_DURATION_MS = 5_000L

    fun tryShow(context: Context, projection: NativeHeadsetProjection): Boolean = runCatching {
        show(context, projection)
    }.getOrElse { failure ->
        ModuleLog.debug(TAG, "not submitted: ${failure.javaClass.name}")
        false
    }

    private fun show(context: Context, projection: NativeHeadsetProjection): Boolean {
        if (!projection.canShowConnectionHint) {
            ModuleLog.debug(TAG, "not submitted: usable ear battery unavailable")
            return false
        }
        // The charging-case reading is deliberately unused: the connected island payload carries the
        // left and right ear areas only (island-spec §4.1, the x0.j.h idx1/idx3 pair).
        val left = islandArea(projection.left) { resolveColor(context, it) }
        val right = islandArea(projection.right) { resolveColor(context, it) }
        // Constructing the real host parameter classes doubles as the shape probe: a build without
        // them cannot parse the JSON anyway, so submitting would only log noise.
        val param = buildHostParam(context.classLoader, left, right)
            ?: run {
                ModuleLog.debug(TAG, "not submitted: island param classes absent")
                return false
            }
        val json = islandParamJson(param) ?: run {
            ModuleLog.debug(TAG, "not submitted: no battery areas to render")
            return false
        }
        val bundle = Bundle()
        islandBundleEntries(json, CONNECT_DURATION_MS).forEach { it.applyTo(bundle) }
        val statusBar = context.getSystemService(STATUS_BAR_SERVICE) ?: run {
            ModuleLog.debug(TAG, "not submitted: status bar service absent")
            return false
        }
        statusBar.javaClass.getMethod("setStatus", Int::class.javaPrimitiveType, String::class.java,
            Bundle::class.java).invoke(statusBar, STATUS_CODE, STRONG_TOAST_ACTION, bundle)
        ModuleLog.debug(TAG, "setStatus probe submitted address=${maskBluetoothAddress(projection.address)} " +
            "left=${projection.left.percent} right=${projection.right.percent}")
        return true
    }

    /**
     * Instantiates the host parameter tree through its public Builder (island-spec §3/§6; host102 smali
     * `MiuiHeadsetIslandParam$Builder`: public `init()`, `setLeftText`/`setLeftIcon`/`setRightText`/
     * `setRightIcon` each returning the Builder, and `create()` calling
     * `new MiuiHeadsetIslandParam(left, right)`). The named setters remove the silent left/right swap
     * risk of the two same-typed constructor parameters. A side without a reading never gets a setter
     * call, so the Builder leaves that ViewArea field null and Gson omits the whole side (spec §3).
     *
     * TODO(icons): the first release renders a text-only island, iconType=0 with an empty
     * iconResName. Real artwork needs the mp4 resource route of island-spec §4.3 (a FileProvider URI
     * SystemUI can read, plus the grant), so it stays deferred until device checks confirm the text
     * island renders.
     */
    // These are the scoped host's plugin classes, not Android hidden framework APIs.
    @SuppressLint("PrivateApi")
    private fun buildHostParam(loader: ClassLoader, left: IslandArea?, right: IslandArea?): Any? {
        val paramClass = loader.loadClass(PARAM_CLASS)
        val textClass = loader.loadClass("$PARAM_CLASS\$TextParams")
        val iconClass = loader.loadClass("$PARAM_CLASS\$IconParams")
        val builderClass = loader.loadClass("$PARAM_CLASS\$Builder")
        val textCtor = textClass.getConstructor(String::class.java, java.lang.Integer::class.java,
            Boolean::class.javaPrimitiveType)
        val iconCtor = iconClass.getConstructor(String::class.java, java.lang.Integer::class.java,
            String::class.java, String::class.java)
        val builder = builderClass.getConstructor().newInstance()
        fun applySide(area: IslandArea?, setText: String, setIcon: String, textType: Class<*>,
            iconParamType: Class<*>) {
            val text = area?.textParams ?: return
            NativeMembers.method(builderClass, setText, textType)
                .invoke(builder, textCtor.newInstance(text.text, text.textColor, text.turnAnim))
            area.iconParams?.let { icon ->
                NativeMembers.method(builderClass, setIcon, iconParamType).invoke(builder,
                    iconCtor.newInstance(icon.iconResName, icon.iconType, icon.iconFormat, icon.category))
            }
        }
        applySide(left, "setLeftText", "setLeftIcon", textClass, iconClass)
        applySide(right, "setRightText", "setRightIcon", textClass, iconClass)
        val create = NativeMembers.method(builderClass, "create")
        require(create.returnType == paramClass) { "Unexpected native island Builder: create" }
        return create.invoke(builder)
    }

    /**
     * Resolves the plugin color resource the host itself would have used (island-spec §4.1). If this
     * context cannot resolve it, the color stays null so the serializer omits textColor entirely
     * rather than submitting a raw resource id as if it were an ARGB value; SystemUI keeps its default.
     */
    private fun resolveColor(context: Context, colorRes: Int): Int? =
        runCatching { context.getColor(colorRes) }.getOrNull()
}

/** Which island path the runtime should take, decided without touching any renderer. */
internal enum class NativeIslandRoute { NATIVE_SHOWN, THIRD_PARTY, NOT_SHOWN }

/**
 * The coexistence gate. On builds whose ownership contract is confirmed absent the native hint cannot
 * be trusted: the host API catches all of its own failures and reports success without rendering
 * anything, so on device its submission always "succeeded" while no island ever appeared (device log
 * 2026-10-31 00:47). Those builds go straight to the third-party renderer and the native probe is not
 * even evaluated. On contract-bearing builds the native hint decides alone, exactly as before: shown
 * wins, declined means nothing shows, and the new renderer is never admitted.
 */
internal fun nativeIslandAdmission(
    contract: OwnershipContractState,
    nativeShown: () -> Boolean,
): NativeIslandRoute = when {
    contract == OwnershipContractState.MISSING -> NativeIslandRoute.THIRD_PARTY
    nativeShown() -> NativeIslandRoute.NATIVE_SHOWN
    else -> NativeIslandRoute.NOT_SHOWN
}

/** Gson-shaped holder mirroring MiuiHeadsetIslandParam$TextParams (island-spec §3). */
internal data class IslandTextParams(val text: String?, val textColor: Int?, val turnAnim: Boolean)

/** Gson-shaped holder mirroring MiuiHeadsetIslandParam$IconParams (island-spec §3). */
internal data class IslandIconParams(val iconResName: String?, val iconType: Int?,
    val iconFormat: String?, val category: String?)

/** Gson-shaped holder mirroring MiuiHeadsetIslandParam$ViewArea (island-spec §3). */
internal data class IslandArea(val textParams: IslandTextParams?, val iconParams: IslandIconParams?)

/** "-" or an absent reading means no text area at all (island-spec §4.1 step 6). */
internal fun islandBatteryText(reading: BatteryReading): String? =
    if (reading.available) reading.percent?.let { "$it%" } else null

/** ≤20% uses the low-battery color, anything above uses the normal color (island-spec §4.1). */
internal fun islandTextColorRes(percent: Int): Int =
    if (percent <= NativeIslandPresentationConstants.LOW_BATTERY_THRESHOLD_PERCENT)
        NativeIslandPresentationConstants.LOW_BATTERY_COLOR_RES
    else NativeIslandPresentationConstants.NORMAL_BATTERY_COLOR_RES

/** The text-only v1 area: turnAnim true, iconType 0 with every icon name field empty (spec §3). */
internal fun islandArea(text: String?, textColor: Int?): IslandArea? = text?.let {
    IslandArea(IslandTextParams(it, textColor, true), IslandIconParams(null, 0, null, null))
}

internal fun islandArea(reading: BatteryReading, resolve: (Int) -> Int?): IslandArea? =
    islandBatteryText(reading)?.let {
        islandArea(it, resolve(islandTextColorRes(reading.percent ?: 0)))
    }

/** Serializes the constructed host object with its declared field names, nulls omitted (spec §3). */
internal fun islandParamJson(param: Any): String? {
    val left = runCatching { islandAreaFromHost(NativeMembers.get(param, "left")) }.getOrNull()
    val right = runCatching { islandAreaFromHost(NativeMembers.get(param, "right")) }.getOrNull()
    return islandParamJson(left, right)
}

private fun islandAreaFromHost(area: Any?): IslandArea? = area?.let {
    val text = NativeMembers.get(it, "textParams")?.let { t ->
        IslandTextParams(NativeMembers.get(t, "text") as? String,
            NativeMembers.get(t, "textColor") as? Int,
            NativeMembers.get(t, "turnAnim") as? Boolean ?: false)
    }
    val icon = NativeMembers.get(it, "iconParams")?.let { i ->
        IslandIconParams(NativeMembers.get(i, "iconResName") as? String,
            NativeMembers.get(i, "iconType") as? Int,
            NativeMembers.get(i, "iconFormat") as? String,
            NativeMembers.get(i, "category") as? String)
    }
    IslandArea(text, icon)
}

/** A null field is omitted and primitives always appear, matching the host Gson shape (spec §3). */
internal fun islandParamJson(left: IslandArea?, right: IslandArea?): String? = jsonObject(
    "left" to left?.let(::islandAreaJson), "right" to right?.let(::islandAreaJson),
)

private fun islandAreaJson(area: IslandArea): String? = jsonObject(
    "textParams" to area.textParams?.let {
        jsonObject("text" to it.text?.let(::jsonString),
            "textColor" to it.textColor?.toString(), "turnAnim" to it.turnAnim.toString())
    },
    "iconParams" to area.iconParams?.let {
        jsonObject("iconResName" to it.iconResName?.let(::jsonString),
            "iconType" to it.iconType?.toString(),
            "iconFormat" to it.iconFormat?.let(::jsonString),
            "category" to it.category?.let(::jsonString))
    },
)

internal fun jsonObject(vararg fields: Pair<String, String?>): String? =
    fields.filter { it.second != null }
        .joinToString(separator = ",", prefix = "{", postfix = "}") { "\"${it.first}\":${it.second}" }
        .takeIf { it != "{}" }

internal fun jsonString(value: String): String = buildString {
    append('"')
    for (ch in value) when (ch) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (ch.code < 0x20) {
            append("\\u").append(ch.code.toString(16).padStart(4, '0'))
        } else append(ch)
    }
    append('"')
}

/** How a key gets into the submission Bundle; NULL_PARCELABLE mirrors the host's null target. */
internal enum class IslandBundleKind { STRING, LONG, NULL_PARCELABLE }

internal data class IslandBundleEntry(val key: String, val value: Any?, val kind: IslandBundleKind) {
    /** Only ever called on the freshly created island Bundle, never on host-owned state. */
    fun applyTo(bundle: Bundle) = when (kind) {
        IslandBundleKind.STRING -> bundle.putString(key, value as String)
        IslandBundleKind.LONG -> bundle.putLong(key, value as Long)
        IslandBundleKind.NULL_PARCELABLE -> bundle.putParcelable(key, null as Parcelable?)
    }
}

/** The verbatim Bundle key table of island-spec §2.2 (the Q2-only `param` key does not apply). */
internal fun islandBundleEntries(islandParam: String, durationMs: Long): List<IslandBundleEntry> = listOf(
    IslandBundleEntry("notifyId", NativeIslandPresentationConstants.NOTIFY_ID, IslandBundleKind.STRING),
    IslandBundleEntry("strong_toast_category", NativeIslandPresentationConstants.CONNECT_CATEGORY,
        IslandBundleKind.STRING),
    IslandBundleEntry("status_bar_strong_toast",
        NativeIslandPresentationConstants.SHOW_CUSTOM_STRONG_TOAST, IslandBundleKind.STRING),
    IslandBundleEntry("package_name", NativeIslandPresentationConstants.HOST_PACKAGE_NAME,
        IslandBundleKind.STRING),
    IslandBundleEntry("island_param", islandParam, IslandBundleKind.STRING),
    IslandBundleEntry("duration", durationMs, IslandBundleKind.LONG),
    IslandBundleEntry("target", null, IslandBundleKind.NULL_PARCELABLE),
)

/** Constants shared with the renderer body and the JVM tests of the payload shape. */
internal object NativeIslandPresentationConstants {
    const val NOTIFY_ID = "headset_wear_notification"
    const val CONNECT_CATEGORY = "video_text_text_video"
    const val SHOW_CUSTOM_STRONG_TOAST = "show_custom_strong_toast"
    const val HOST_PACKAGE_NAME = "com.xiaomi.bluetooth"
    const val LOW_BATTERY_COLOR_RES = 0x7f060070
    const val NORMAL_BATTERY_COLOR_RES = 0x7f060071
    const val LOW_BATTERY_THRESHOLD_PERCENT = 20
}
