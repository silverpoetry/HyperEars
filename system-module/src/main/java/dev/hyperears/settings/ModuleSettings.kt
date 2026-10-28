package dev.hyperears.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import java.util.HashSet

/** Shared destination for headset card settings, battery notifications and connection windows. */
enum class MoreSettingsTarget {
    SYSTEM_SETTINGS,
    VENDOR_APP,
    HYPEREARS,
    ;

    companion object {
        fun fromStoredValue(value: String?): MoreSettingsTarget? =
            entries.firstOrNull { it.name == value }
    }
}

enum class HeadsetConnectionPresentation(val title: String) {
    SMART("智能"),
    BATTERY_POPUP("仅弹窗"),
    SYSTEM_HINT("仅超级岛"),
    OFF("关闭"),
    ;

    companion object {
        // Persist names, not menu positions: existing explicit selections survive upgrades.
        fun fromStoredValue(value: String?): HeadsetConnectionPresentation =
            entries.firstOrNull { it.name == value } ?: SMART
    }
}

/** User-controlled integration, navigation and diagnostics policy. */
data class ModuleSettings(
    val moreSettingsTarget: MoreSettingsTarget = MoreSettingsTarget.SYSTEM_SETTINGS,
    val yieldToVendorControlApp: Boolean = false,
    val modulePaused: Boolean = false,
    val diagnosticLogging: Boolean = false,
    val disabledAdapterIds: Set<String> = emptySet(),
    val headsetConnectionPresentation: HeadsetConnectionPresentation = HeadsetConnectionPresentation.SMART,
    val headsetDetailNotification: Boolean = false,
    val connectionVolume: ConnectionVolumePreset = ConnectionVolumePreset.OFF,
)

/**
 * Settings shared with injected processes through libxposed RemotePreferences.
 *
 * Writes deliberately use the platform editor and synchronous [SharedPreferences.Editor.commit]:
 * callers need the result before updating the local mirror or reporting a successful change.
 */
@SuppressLint("ApplySharedPref", "UseKtx")
object ModuleSettingsStore {
    const val PREFERENCES_GROUP = "hyper_ears_settings"

    private const val MORE_SETTINGS_TARGET = "more_settings_target"
    private const val LEGACY_PREFER_VENDOR_CONTROL_APP = "prefer_vendor_control_app"
    private const val YIELD_TO_VENDOR_CONTROL_APP = "yield_to_vendor_control_app"
    private const val MODULE_PAUSED = "module_paused"
    private const val DIAGNOSTIC_LOGGING = "diagnostic_logging"
    private const val DISABLED_ADAPTER_IDS = "disabled_adapter_ids"
    private const val HEADSET_CONNECTION_PRESENTATION = "headset_connection_presentation"
    private const val HEADSET_DETAIL_NOTIFICATION = "headset_detail_notification"
    private const val CONNECTION_VOLUME = "connection_volume"
    private const val REMOTE_MIGRATION_COMPLETE = "remote_migration_complete"
    private const val REMOTE_WRITE_PENDING = "remote_write_pending"

    fun read(preferences: SharedPreferences): ModuleSettings = ModuleSettings(
        moreSettingsTarget = readMoreSettingsTarget(preferences),
        yieldToVendorControlApp = preferences.getBoolean(YIELD_TO_VENDOR_CONTROL_APP, false),
        modulePaused = preferences.getBoolean(MODULE_PAUSED, false),
        diagnosticLogging = preferences.getBoolean(DIAGNOSTIC_LOGGING, false),
        headsetConnectionPresentation = HeadsetConnectionPresentation.fromStoredValue(
            preferences.getString(HEADSET_CONNECTION_PRESENTATION, null),
        ),
        headsetDetailNotification = preferences.getBoolean(HEADSET_DETAIL_NOTIFICATION, false),
        connectionVolume = ConnectionVolumePreset.fromStoredValue(preferences.getString(CONNECTION_VOLUME, null)),
        disabledAdapterIds = preferences
            .getStringSet(DISABLED_ADAPTER_IDS, emptySet())
            .orEmpty()
            .toSet(),
    )

    fun write(preferences: SharedPreferences, settings: ModuleSettings): Boolean =
        preferences.edit()
            .putString(MORE_SETTINGS_TARGET, settings.moreSettingsTarget.name)
            // Keep the former boolean synchronized so a temporary downgrade retains the closest
            // representable behavior. HYPEREARS degrades to the legacy system-settings path.
            .putBoolean(
                LEGACY_PREFER_VENDOR_CONTROL_APP,
                settings.moreSettingsTarget == MoreSettingsTarget.VENDOR_APP,
            )
            .putBoolean(YIELD_TO_VENDOR_CONTROL_APP, settings.yieldToVendorControlApp)
            .putBoolean(MODULE_PAUSED, settings.modulePaused)
            .putBoolean(DIAGNOSTIC_LOGGING, settings.diagnosticLogging)
            .putString(HEADSET_CONNECTION_PRESENTATION, settings.headsetConnectionPresentation.name)
            .putBoolean(HEADSET_DETAIL_NOTIFICATION, settings.headsetDetailNotification)
            .putString(CONNECTION_VOLUME, settings.connectionVolume.name)
            .putStringSet(DISABLED_ADAPTER_IDS, settings.disabledAdapterIds.toRemotePreferencesSet())
            .commit()

    fun readLocal(context: Context): ModuleSettings = read(localPreferences(context))

    fun writeLocal(
        context: Context,
        settings: ModuleSettings,
        pendingRemoteWrite: Boolean,
    ): Boolean {
        val preferences = localPreferences(context)
        val settingsCommitted = write(preferences, settings)
        val metadataCommitted = preferences.edit()
            .putBoolean(REMOTE_WRITE_PENDING, pendingRemoteWrite)
            .commit()
        return settingsCommitted && metadataCommitted
    }

    /**
     * Migrates the former app-local settings once, then treats libxposed RemotePreferences as
     * the single runtime source of truth. Local preferences remain only as an offline UI mirror.
     */
    fun synchronizeRemote(context: Context, remote: SharedPreferences): ModuleSettings {
        val local = localPreferences(context)
        val migrationRequired = !local.getBoolean(REMOTE_MIGRATION_COMPLETE, false)
        val pendingWrite = local.getBoolean(REMOTE_WRITE_PENDING, false)
        val remoteConfigured = PREFERENCE_KEYS.any(remote::contains)
        val navigationMigrationRequired =
            !remote.contains(MORE_SETTINGS_TARGET) &&
                remote.contains(LEGACY_PREFER_VENDOR_CONTROL_APP)
        val effective = if (migrationRequired || pendingWrite || !remoteConfigured) {
            read(local).also { write(remote, it) }
        } else {
            read(remote)
        }
        if (navigationMigrationRequired) write(remote, effective)
        write(local, effective)
        local.edit()
            .putBoolean(REMOTE_MIGRATION_COMPLETE, true)
            .putBoolean(REMOTE_WRITE_PENDING, false)
            .commit()
        return effective
    }

    private fun localPreferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFERENCES_GROUP, Context.MODE_PRIVATE)

    private fun readMoreSettingsTarget(preferences: SharedPreferences): MoreSettingsTarget {
        val stored = runCatching { preferences.getString(MORE_SETTINGS_TARGET, null) }.getOrNull()
        val legacyPreference = if (preferences.contains(LEGACY_PREFER_VENDOR_CONTROL_APP)) {
            preferences.getBoolean(LEGACY_PREFER_VENDOR_CONTROL_APP, false)
        } else {
            null
        }
        return resolveMoreSettingsTarget(stored, legacyPreference)
    }

    private val PREFERENCE_KEYS = setOf(
        MORE_SETTINGS_TARGET,
        LEGACY_PREFER_VENDOR_CONTROL_APP,
        YIELD_TO_VENDOR_CONTROL_APP,
        MODULE_PAUSED,
        DIAGNOSTIC_LOGGING,
        DISABLED_ADAPTER_IDS,
        HEADSET_CONNECTION_PRESENTATION,
        HEADSET_DETAIL_NOTIFICATION,
        CONNECTION_VOLUME,
    )
}

internal fun resolveMoreSettingsTarget(
    storedValue: String?,
    legacyPreferVendorApp: Boolean?,
): MoreSettingsTarget = MoreSettingsTarget.fromStoredValue(storedValue)
    ?: if (legacyPreferVendorApp == true) {
        MoreSettingsTarget.VENDOR_APP
    } else {
        MoreSettingsTarget.SYSTEM_SETTINGS
    }

/**
 * RemotePreferences serializes values in the module process and deserializes them in the LSPosed
 * daemon. Copy sets to a Java platform collection so an empty Kotlin singleton never crosses that
 * process boundary and requires the daemon to load Kotlin runtime classes.
 */
internal fun Set<String>.toRemotePreferencesSet(): HashSet<String> = HashSet(this)
