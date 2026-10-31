package dev.hyperears.presentation

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import dev.hyperears.hook.HookContext
import dev.hyperears.hook.ModuleLog
import dev.hyperears.hook.maskBluetoothAddress

/** Native layout/actions with call-scoped projection and explicit ownership of our publications. */
internal class NativeDetailNotification(
    private val context: Context,
    private val methods: NativeDetailNotificationResolver.Methods,
    private val openSettings: (address: String, connection: String) -> Boolean,
) : HookContext() {
    private data class Call(
        val projection: NativeHeadsetProjection,
        val publication: NativeNotificationRegistry.Publication? = null,
    )

    private val current = ThreadLocal<Call?>()
    // Updates, clicks and cleanup run on the native host's main thread.
    private val registry = NativeNotificationRegistry()
    private val notifications = context.getSystemService(NotificationManager::class.java)

    override fun install() {
        // These are the exact two callers we invoke. Their short support/notify callees must
        // reach the scoped hooks even when the host APK has already been AOT compiled.
        check(module.deoptimize(methods.create) && module.deoptimize(methods.cancel)) {
            "Native notification call boundaries unavailable"
        }
        hookBefore(methods.support) {
            if (current.get()?.projection?.address == args[1]) result = true
        }
        hookBefore(methods.notificationType) {
            current.get()?.projection?.takeIf { it.singleBattery }?.let { result = "singlebatteryheadset" }
        }
        val notify = NotificationManager::class.java.getMethod("notify", String::class.java,
            Int::class.javaPrimitiveType, Notification::class.java)
        module.hook(notify).intercept { chain ->
            val publication = current.get()?.publication ?: return@intercept chain.proceed()
            val address = publication.projection.address
            if (chain.args[0] != tag(address) || chain.args[1] != NOTIFICATION_ID) return@intercept chain.proceed()
            val original = chain.args[2] as? Notification ?: return@intercept chain.proceed()
            // Native construction stays intact. Mark only this synchronous call's exact tag/ID,
            // before enqueueing, rather than trying to infer ownership from a later postTime.
            val owned = original.clone().apply {
                extras = Bundle(extras ?: Bundle.EMPTY).apply {
                    putString(EXTRA_TOKEN, publication.token)
                    putString(EXTRA_ADDRESS, address)
                }
                flags = flags or Notification.FLAG_ONLY_ALERT_ONCE
                contentIntent = PendingIntent.getBroadcast(context, 0,
                    Intent(CLICK_ACTION).setPackage(context.packageName)
                        .setIdentifier(clickId(address, publication.token))
                        .putExtra("bluetoothaddress", address)
                        .putExtra(EXTRA_TOKEN, publication.token),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            chain.proceed(arrayOf(tag(address), NOTIFICATION_ID, owned)).also {
                registry.posted(publication)
                ModuleLog.debug(TAG) {
                    "submitted address=${maskBluetoothAddress(address)} " +
                        "single=${publication.projection.singleBattery} " +
                        "left=${publication.projection.left.percent} right=${publication.projection.right.percent} " +
                        "case=${publication.projection.chargingCase.percent} tag=BTHeadset id=$NOTIFICATION_ID"
                }
            }
        }
        // Native base and plugin services can both receive the original detail action. Use our
        // own package-scoped action so neither can open a model-specific Xiaomi settings page.
        // This receiver lives with the host runtime, including while the setting is disabled.
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != CLICK_ACTION) return
                val address = intent.getStringExtra("bluetoothaddress") ?: return
                val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
                if (intent.identifier != clickId(address, token)) return
                // A click can remove the visible notification before this receiver executes.
                // Validate the action and current connection, never a notification timestamp.
                val publication = registry.forClick(address, token)
                val opened = publication != null && runCatching { openSettings(address, publication.connection) }
                    .onFailure { ModuleLog.warn(TAG, "notification navigation failed", it) }.getOrDefault(false)
                ModuleLog.debug(TAG, "click address=${maskBluetoothAddress(address)} " +
                    "current=${publication != null} opened=$opened")
            }
        }, IntentFilter(CLICK_ACTION), Context.RECEIVER_NOT_EXPORTED)
    }

    fun update(p: NativeHeadsetProjection, device: BluetoothDevice, connection: String) {
        if (!p.hasBattery) {
            remove(p.address)
            return
        }
        val visible = visible(p.address)
        if (visible != null && visible.token == null) {
            ModuleLog.debug(TAG, "retaining native notification address=${maskBluetoothAddress(p.address)}")
            return
        }
        val publication = registry.prepare(p, connection, visible) ?: return
        withCall(Call(p, publication)) {
            val values = if (p.singleBattery) intArrayOf(127, p.left.percent ?: 127, 127)
            else intArrayOf(p.left.percent ?: 127, p.right.percent ?: 127, p.chargingCase.percent ?: 127)
            methods.create.invoke(null, context, device, values, true, false)
        }
        // The notify hook commits only after enqueue succeeds. The native builder can return
        // without posting (or catch an exception); that must never create a fictitious receipt.
        if (registry.forClick(p.address, publication.token) !== publication) {
            ModuleLog.debug(TAG, "native builder did not submit address=${maskBluetoothAddress(p.address)}")
        }
    }

    fun remove(address: String) {
        val publication = registry.retire(address, visible(address)) ?: return
        val device = context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address) ?: return
        withCall(Call(publication.projection)) { methods.cancel.invoke(null, context, device) }
        // Preserve the native channel and the user's per-device notification preferences.
    }

    fun clear() = registry.addresses.forEach(::remove)

    private fun visible(address: String): NativeNotificationRegistry.Visible? = notifications.activeNotifications
        .singleOrNull { it.tag == tag(address) && it.id == NOTIFICATION_ID }
        ?.let {
            val extras = it.notification.extras ?: Bundle.EMPTY
            NativeNotificationRegistry.Visible(
                extras.getString(EXTRA_TOKEN)?.takeIf { extras.getString(EXTRA_ADDRESS) == address },
            )
        }

    private fun withCall(call: Call, action: () -> Unit) {
        val previous = current.get()
        current.set(call)
        try { action() } finally { if (previous == null) current.remove() else current.set(previous) }
    }

    companion object {
        private const val TAG = "NativeDetail"
        private const val NOTIFICATION_ID = 10003
        private const val CLICK_ACTION = "dev.hyperears.action.NATIVE_DETAIL_SETTINGS"
        private const val EXTRA_TOKEN = "dev.hyperears.native_notification_token"
        private const val EXTRA_ADDRESS = "dev.hyperears.native_notification_address"
        private fun tag(address: String) = "BTHeadset$address"
        private fun clickId(address: String, token: String) = "dev.hyperears.detail:$address:$token"
    }
}
