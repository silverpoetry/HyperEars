package dev.hyperears.presentation

import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import dev.hyperears.hook.HookContext
import dev.hyperears.hook.ModuleLog
import java.util.concurrent.ConcurrentHashMap

/** Original notification builder/actions, scoped data projection and exact instance cleanup. */
internal class NativeDetailNotification(
    private val context: Context,
    private val methods: NativeDetailNotificationResolver.Methods,
    private val openSettings: (address: String, connection: String) -> Boolean,
) : HookContext() {
    private data class Entry(val device: BluetoothDevice, val projection: NativeHeadsetProjection,
        val connection: String, val postedAt: Long)
    private val current = ThreadLocal<NativeHeadsetProjection?>()
    private val entries = ConcurrentHashMap<String, Entry>()
    private val notifications = context.getSystemService(NotificationManager::class.java)

    override fun install() {
        hookBefore(methods.support) {
            if (current.get()?.address == args[1]) result = true
        }
        hookBefore(methods.notificationType) {
            current.get()?.takeIf { it.singleBattery }?.let { result = "singlebatteryheadset" }
        }
        hookBefore(methods.click) {
            val intent = args[1] as? Intent ?: return@hookBefore
            if (intent.action != "com.android.bluetooth.headset.click.detail_notification") return@hookBefore
            val address = intent.getStringExtra("bluetoothaddress") ?: return@hookBefore
            val entry = entries[address] ?: return@hookBefore
            // A native device may have replaced our notification with the same tag and ID.
            if (notification(address)?.postTime != entry.postedAt) return@hookBefore
            if (openSettings(address, entry.connection)) result = null
        }
    }

    fun update(p: NativeHeadsetProjection, device: BluetoothDevice, connection: String) {
        if (!p.hasBattery) return
        val old = entries[p.address]
        val existing = notification(p.address)
        if (existing != null && (old == null || existing.postTime != old.postedAt)) return
        // Keep the native delete action. A cleared notification can be posted by a later report,
        // including one with the same battery values; only a still-visible copy is deduplicated.
        if (existing != null && old?.projection == p && old.connection == connection) return
        withProjection(p) {
            val values = if (p.singleBattery) intArrayOf(127, p.left.percent ?: 127, 127)
            else intArrayOf(p.left.percent ?: 127, p.right.percent ?: 127, p.chargingCase.percent ?: 127)
            methods.create.invoke(null, context, device, values, true, false)
        }
        val posted = notification(p.address) ?: return
        entries[p.address] = Entry(device, p, connection, posted.postTime)
        ModuleLog.debug("NativeDetail", "published revision of native battery notification")
    }

    fun remove(address: String) {
        val entry = entries.remove(address) ?: return
        val visible = notification(address)
        // Cancel only our exact visible post, including when ownership changes. A replacement
        // posted by the native owner has a different postTime and must remain untouched.
        if (visible == null || visible.postTime != entry.postedAt) return
        withProjection(entry.projection) { methods.cancel.invoke(null, context, entry.device) }
        // Preserve the native channel and the user's per-device notification preferences.
    }

    fun clear() {
        entries.keys.toList().forEach { remove(it) }
    }

    private fun notification(address: String) = notifications.activeNotifications
        .singleOrNull { it.tag == tag(address) && it.id == NOTIFICATION_ID }

    private fun withProjection(p: NativeHeadsetProjection, action: () -> Unit) {
        val previous = current.get()
        current.set(p)
        try { action() } finally { if (previous == null) current.remove() else current.set(previous) }
    }

    companion object {
        private const val NOTIFICATION_ID = 10003
        private fun tag(address: String) = "BTHeadset$address"
    }
}
