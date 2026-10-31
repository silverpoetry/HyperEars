package dev.hyperears.bridge

import android.content.Context
import android.content.Intent
import dev.hyperears.hook.ModuleLog
import dev.hyperears.integration.ControlAppSpec
import dev.hyperears.integration.EarbudState
import dev.hyperears.settings.MoreSettingsTarget

/** Shared destination policy for module-owned cards, notifications and connection windows. */
internal class HeadsetSettingsNavigator(
    private val openSystemSettings: (String) -> Boolean,
    private val openVendorApp: (List<ControlAppSpec>) -> Boolean,
    private val openHyperEars: () -> Boolean,
    private val openNativeSettings: ((String) -> Boolean)? = null,
) {
    constructor(context: Context) : this(
        openSystemSettings = { address -> BluetoothDeviceSettings.open(context, address) },
        openNativeSettings = { address -> BluetoothDeviceSettings.openNative(context,address) },
        openVendorApp = { candidates -> launchVendorApp(context, candidates) },
        openHyperEars = {
            launch(context, Intent(Intent.ACTION_MAIN)
                .setClassName(ModuleContract.MODULE_PACKAGE, "dev.hyperears.MainActivity")
                .addCategory(Intent.CATEGORY_LAUNCHER), ModuleContract.MODULE_PACKAGE)
        },
    )

    private fun openSettings(address:String,state:EarbudState?):Boolean =
        if(state?.address.equals(address,true)&&state?.adapter?.id=="sony-wh-1000xm6"&&state.sessionActive&&openNativeSettings!=null)
            openNativeSettings.invoke(address) else openSystemSettings(address)

    /** Callers supply the current preference and accepted state at the time of the click. */
    fun open(address: String, state: EarbudState?, target: MoreSettingsTarget): Boolean = when (target) {
        MoreSettingsTarget.SYSTEM_SETTINGS -> openSettings(address,state)
        MoreSettingsTarget.VENDOR_APP -> {
            val current = state?.takeIf { it.address == address }
            val candidates = buildList {
                current?.lifecycle?.externalControlApp?.let(::add)
                current?.adapter?.controlApps?.let(::addAll)
            }.distinctBy(ControlAppSpec::packageName)
            (candidates.isNotEmpty() && openVendorApp(candidates)) || openSettings(address,state)
        }
        MoreSettingsTarget.HYPEREARS -> openHyperEars() || openSettings(address,state)
    }

    private companion object {
        const val TAG = "HeadsetSettings"

        fun launchVendorApp(context: Context, candidates: List<ControlAppSpec>): Boolean {
            val (app, intent) = candidates.firstNotNullOfOrNull { app ->
                context.packageManager.getLaunchIntentForPackage(app.packageName)?.let { app to it }
            } ?: return false
            return launch(context, intent, app.packageName)
        }

        fun launch(context: Context, intent: Intent, destination: String): Boolean = runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }.onSuccess {
            ModuleLog.debug(TAG, "opened $destination")
        }.onFailure {
            ModuleLog.warn(TAG, "unable to open $destination", it)
        }.isSuccess
    }
}
