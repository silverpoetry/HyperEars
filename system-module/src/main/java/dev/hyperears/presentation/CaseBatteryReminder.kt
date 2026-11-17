package dev.hyperears.presentation

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import dev.hyperears.R
import dev.hyperears.bridge.ModuleContract
import dev.hyperears.hook.ModuleLog
import dev.hyperears.hook.maskBluetoothAddress
import dev.hyperears.integration.EarbudState
import dev.hyperears.settings.CaseBatteryAlarmMode
import dev.hyperears.settings.ModuleSettings
import dev.hyperears.settings.ModuleSettingsRuntime
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Surfaces model-agnostic case-battery alarms inside the MiLink host process.
 *
 * Decision logic lives in [BatteryReminderPolicy]; this class only owns per-device policy
 * instances and Android notification plumbing. Every notification failure is isolated so the
 * surrounding state pipeline is never disturbed. Adapters without a verified case reading never
 * reach [BatteryReminderPolicy] with a usable value and therefore stay silent.
 *
 * Alarm behavior follows [ModuleSettings] live through the default [settingsProvider]:
 * disabling suppresses new evaluations while keeping per-device memory, and a threshold change
 * rebuilds the policy so the new limit applies from the next accepted state update.
 *
 * Text comes from the module APK through [createPackageContext] — the host process cannot
 * resolve HyperEars resource IDs from its own table — with English literals as the fallback when
 * the module context is unavailable. The small icon stays a framework drawable because adaptive
 * launcher icons do not render as valid status-bar icons.
 */
internal class CaseBatteryReminder(
    private val context: Context,
    private val settingsProvider: () -> ModuleSettings = { ModuleSettingsRuntime.current },
) {
    /** Snapshot of the reminder-relevant settings a policy was built with. */
    private data class Config(
        val lowPercent: Int,
        val mode: CaseBatteryAlarmMode,
    )

    private class PolicyHolder(
        val config: Config,
        val policy: BatteryReminderPolicy,
    )

    private val policies = ConcurrentHashMap<String, PolicyHolder>()

    /** Last known display name per address, so delayed confirmations keep the same title. */
    private val deviceNames = ConcurrentHashMap<String, String>()

    /** Addresses with a vanish confirmation already queued on the handler. */
    private val queuedVanishConfirms: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val handler = Handler(Looper.getMainLooper())
    private val notifications = runCatching {
        context.getSystemService(NotificationManager::class.java)
    }.getOrNull()
    private val moduleResources: Resources? = runCatching {
        context.createPackageContext(
            ModuleContract.MODULE_PACKAGE,
            Context.CONTEXT_IGNORE_SECURITY,
        ).resources
    }.getOrNull()

    /** Evaluates one accepted state update; the final inactive update closes the session. */
    fun onStateChanged(state: EarbudState) {
        val address = state.address?.uppercase(Locale.ROOT) ?: return
        val liveName = state.deviceName?.takeIf(String::isNotBlank) ?: state.adapter?.displayName
        if (liveName != null) deviceNames[address] = liveName
        val name = liveName ?: deviceNames[address]
        if (!state.sessionActive) {
            // The policy instance survives: only its session memory retires, so the cross-
            // channel suppression state carries over to the next connection.
            val policy = policies[address]?.policy ?: return
            val reminder = runCatching { policy.onSessionEnded() }.getOrElse {
                ModuleLog.warn(TAG, "case battery reminder evaluation failed", it)
                emptyList()
            }
            // Judge against the live mode so switching to a connection-free mode silences the
            // disconnect alarm of a policy that was still built with both channels.
            if (settingsProvider().caseBatteryAlarmMode.alarmsOnConnection) {
                reminder.forEach { post(address, name, it) }
            }
            return
        }
        val settings = settingsProvider()
        if (settings.caseBatteryAlarmMode == CaseBatteryAlarmMode.OFF) return
        val lowPercent = settings.caseBatteryLowPercent.coerceIn(0, 100)
        val config = Config(lowPercent = lowPercent, mode = settings.caseBatteryAlarmMode)
        val holder = policies[address]
        val policy = if (holder?.config == config) {
            holder.policy
        } else {
            BatteryReminderPolicy(
                lowCasePercent = config.lowPercent,
                connectionAlarmsEnabled = config.mode.alarmsOnConnection,
                handlingAlarmsEnabled = config.mode.alarmsOnHandling,
            ).also { rebuilt -> policies[address] = PolicyHolder(config, rebuilt) }
        }
        runCatching {
            policy.onBatteryUpdate(state.battery).forEach { post(address, name, it) }
            // A vanished reading is ambiguous (take-out vs lid closing); confirm it after the
            // grace delay. The check self-invalidates when the session ended meanwhile, so a
            // lid close never produces the phantom take-out alarm. While the vanish stays
            // pending, later frames must not queue duplicate confirmations.
            if (policy.hasPendingVanish() && queuedVanishConfirms.add(address)) {
                scheduleVanishConfirm(address, name, policy)
            }
        }.onFailure {
            ModuleLog.warn(TAG, "case battery reminder evaluation failed", it)
        }
    }

    /** Confirms a pending take-out once the grace delay proved the session survived. */
    private fun scheduleVanishConfirm(
        address: String,
        name: String?,
        policy: BatteryReminderPolicy,
    ) {
        handler.postDelayed(
            {
                queuedVanishConfirms.remove(address)
                runCatching {
                    // Confirm only the exact policy instance that owns the vanish: a rebuilt
                    // or retired policy invalidates it, and a live handling-free mode silences
                    // a confirmation that was scheduled under an older configuration.
                    if (policies[address]?.policy !== policy) return@runCatching
                    if (!settingsProvider().caseBatteryAlarmMode.alarmsOnHandling) {
                        return@runCatching
                    }
                    policy.confirmVanish()?.let { post(address, name, it) }
                }.onFailure {
                    ModuleLog.warn(TAG, "case battery reminder evaluation failed", it)
                }
            },
            BatteryReminderPolicy.DEFAULT_VANISH_GRACE_MILLIS,
        )
    }

    fun clear() {
        handler.removeCallbacksAndMessages(null)
        queuedVanishConfirms.clear()
        policies.clear()
        deviceNames.clear()
    }

    // The reminder posts inside the MiLink host process, whose notification permission grant
    // applies; the module manifest never declares POST_NOTIFICATIONS for its own app.
    @SuppressLint("NotificationPermission")
    private fun post(address: String, name: String?, reminder: BatteryReminder) {
        val manager = notifications ?: return
        runCatching {
            ensureChannels(manager)
            // Adaptive module icons do not render as status-bar artwork, so the small icon
            // stays a framework drawable inside com.milink.service.
            val notification = Notification.Builder(context, CHANNEL_ALERT)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(title(name))
                .setContentText(contextText(reminder))
                .setAutoCancel(true)
                .apply {
                    launchIntent()?.let { setContentIntent(it) }
                }
                .build()
            manager.notify(tag(address), NOTIFICATION_ID, notification)
            ModuleLog.debug(TAG) {
                "posted low case alarm case=${reminder.casePercent}% " +
                    "enabled=${manager.areNotificationsEnabled()} " +
                    "channel=${channelState(manager)} " +
                    "address=${maskBluetoothAddress(address)}"
            }
        }.onFailure {
            ModuleLog.warn(TAG, "case battery alarm notification failed", it)
        }
    }

    /** Reports the alert channel's current importance so drops by the system are diagnosable. */
    private fun channelState(manager: NotificationManager): String =
        manager.getNotificationChannel(CHANNEL_ALERT)?.importance?.toString() ?: "missing"

    // Fallback literals mirror values/strings.xml; update both together.
    /** The device name follows the session card; a missing name falls back to a generic title. */
    private fun title(name: String?): String = when (name) {
        null -> localized(R.string.case_battery_low_title) ?: "Charging case battery low"
        else -> localized(R.string.case_battery_low_title_named, name)
            ?: "$name charging case battery low"
    }

    private fun contextText(reminder: BatteryReminder): String =
        localized(R.string.case_battery_text, reminder.casePercent)
            ?: "Case battery ${reminder.casePercent}%"

    private fun ensureChannels(manager: NotificationManager) {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                localized(R.string.case_battery_channel_alert) ?: CHANNEL_ALERT_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    /** Resolves a module string in the module resource table; null when unavailable. */
    private fun localized(id: Int, vararg args: Any): String? =
        moduleResources?.getString(id, *args)

    private fun launchIntent(): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(ModuleContract.MODULE_PACKAGE)?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

    private fun tag(address: String) = "HyperEarsCase$address"

    private companion object {
        const val TAG = "CaseBattery"
        const val NOTIFICATION_ID = 10004
        const val CHANNEL_ALERT = "hyperears_case_battery_alert"
        const val CHANNEL_ALERT_NAME = "Case battery alerts"
    }
}
