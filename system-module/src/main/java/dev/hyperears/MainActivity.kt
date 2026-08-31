package dev.hyperears

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.net.toUri
import dev.hyperears.bridge.ModuleContract
import dev.hyperears.diagnostics.AppDiagnosticLog
import dev.hyperears.diagnostics.DiagnosticLogExporter
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.ControlOwnership
import dev.hyperears.integration.NoiseMode
import dev.hyperears.integration.StandardControlRequest
import dev.hyperears.root.RootAction
import dev.hyperears.root.RootActionState
import dev.hyperears.root.RootCommandRunner
import dev.hyperears.settings.ModuleSettings
import dev.hyperears.settings.ModuleSettingsStore
import dev.hyperears.ui.dashboard.DashboardUiState
import dev.hyperears.ui.dashboard.DeviceSessionCollection
import dev.hyperears.ui.dashboard.DeviceSessionReducer
import dev.hyperears.ui.dashboard.DeviceSessionSnapshot
import dev.hyperears.ui.navigation.HyperEarsApp
import dev.hyperears.ui.theme.HyperEarsTheme
import dev.hyperears.ui.theme.UiPreferencesStore
import dev.hyperears.update.ReleaseInfo
import dev.hyperears.update.UpdateCheckCoordinator
import dev.hyperears.update.UpdateCheckPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val sessionCollection = MutableStateFlow(DeviceSessionCollection())
    private val runtimeResponsive = MutableStateFlow(false)
    private val miLinkProcesses = MutableStateFlow<Set<String>>(emptySet())
    private val lastUpdatedAtMillis = MutableStateFlow<Long?>(null)
    private val settings = MutableStateFlow(ModuleSettings())
    private val autoCheckUpdates = MutableStateFlow(true)
    private val rootAvailable = MutableStateFlow<Boolean?>(null)
    private val rootActionState = MutableStateFlow<RootActionState>(RootActionState.Idle)
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val updateCheckPreferences by lazy { UpdateCheckPreferences(this) }
    private val updateCheckCoordinator by lazy {
        UpdateCheckCoordinator(
            preferences = updateCheckPreferences,
            scope = activityScope,
            currentVersion = BuildConfig.VERSION_NAME,
        )
    }
    private val uiPreferencesStore by lazy { UiPreferencesStore(this) }
    private val dashboardRefreshedSessionTokens = mutableSetOf<String>()
    private var remotePreferences: SharedPreferences? = null
    private var activityStarted = false
    private var dashboardVisible = false

    private val createDiagnosticDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { destination ->
        if (destination == null) return@registerForActivityResult
        activityScope.launch {
            val diagnosticEnabled = settings.value.diagnosticLogging
            val result = DiagnosticLogExporter.export(
                context = this@MainActivity,
                destination = destination,
                diagnosticLoggingEnabled = diagnosticEnabled,
            )
            AppDiagnosticLog.record(
                context = this@MainActivity,
                enabled = diagnosticEnabled,
                component = "LogExport",
                message = "success=${result.success} · ${result.detail}",
            )
            Toast.makeText(
                this@MainActivity,
                if (result.success) "日志已导出" else "日志导出失败",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private val preferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { preferences, _ ->
            runOnUiThread {
                settings.value = ModuleSettingsStore.read(preferences)
            }
        }

    private val modulePreferencesListener = object : HyperEarsApplication.PreferencesListener {
        override fun onPreferencesChanged(preferences: SharedPreferences?) {
            runOnUiThread { bindRemotePreferences(preferences) }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ModuleContract.ACTION_STATE_CHANGED -> {
                    with(ModuleContract) {
                        val state = intent.readState()
                        val token = intent.readSessionToken()
                        if (state != null && token != null) {
                            sessionCollection.value = DeviceSessionReducer.reduce(
                                previous = sessionCollection.value,
                                state = state,
                                sessionToken = token,
                            )
                            lastUpdatedAtMillis.value = System.currentTimeMillis()
                            refreshDashboardSessionIfNeeded(state, token)
                        }
                    }
                    runtimeResponsive.value = true
                }

                ModuleContract.ACTION_BRIDGE_STATE_OBSERVED -> {
                    val receipt = with(ModuleContract) {
                        intent.readBridgeReceipt()
                    } ?: return
                    sessionCollection.value = DeviceSessionReducer.acceptBridgeReceipt(
                        previous = sessionCollection.value,
                        receipt = receipt,
                    )
                }

                ModuleContract.ACTION_BRIDGE_RUNTIME_OBSERVED -> {
                    val receipt = with(ModuleContract) {
                        intent.readBridgeRuntimeReceipt()
                    } ?: return
                    miLinkProcesses.value += receipt.consumerProcess
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        settings.value = ModuleSettingsStore.readLocal(this)
        autoCheckUpdates.value = updateCheckPreferences.automaticChecksEnabled
        registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(ModuleContract.ACTION_STATE_CHANGED)
                addAction(ModuleContract.ACTION_BRIDGE_STATE_OBSERVED)
                addAction(ModuleContract.ACTION_BRIDGE_RUNTIME_OBSERVED)
            },
            Context.RECEIVER_EXPORTED,
        )
        setContent {
            val uiPreferences = uiPreferencesStore.state
                .collectAsStateWithLifecycle()
                .value
            val darkTheme = uiPreferences.themeMode.resolveDark(isSystemInDarkTheme())
            LaunchedEffect(darkTheme) {
                val transparent = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        lightScrim = transparent,
                        darkScrim = transparent,
                        detectDarkMode = { darkTheme },
                    ),
                    navigationBarStyle = SystemBarStyle.auto(
                        lightScrim = transparent,
                        darkScrim = transparent,
                        detectDarkMode = { darkTheme },
                    ),
                )
            }
            val activeSessions = sessionCollection
                .collectAsStateWithLifecycle()
                .value
                .sessions
            val online = runtimeResponsive.collectAsStateWithLifecycle().value
            val bridgeProcesses = miLinkProcesses.collectAsStateWithLifecycle().value
            val updatedAt = lastUpdatedAtMillis.collectAsStateWithLifecycle().value
            val currentSettings = settings.collectAsStateWithLifecycle().value
            val hasRoot = rootAvailable.collectAsStateWithLifecycle().value
            val rootAction = rootActionState.collectAsStateWithLifecycle().value
            val updateCheck = updateCheckCoordinator.state
                .collectAsStateWithLifecycle()
                .value
            val automaticUpdates = autoCheckUpdates.collectAsStateWithLifecycle().value
            val systemDensity = LocalDensity.current
            val scaledDensity = remember(systemDensity, uiPreferences.interfaceScale) {
                Density(
                    density = systemDensity.density * uiPreferences.interfaceScale,
                    fontScale = systemDensity.fontScale,
                )
            }
            HyperEarsTheme(preferences = uiPreferences) {
                CompositionLocalProvider(LocalDensity provides scaledDensity) {
                    HyperEarsApp(
                        uiState = DashboardUiState(
                            sessions = activeSessions.values
                                .sortedBy { it.state.deviceName.orEmpty() },
                            runtimeResponsive = online,
                            miLinkProcesses = bridgeProcesses,
                            lastUpdatedAtMillis = updatedAt,
                        ),
                        onRefresh = {
                            requestRuntimeState()
                            activeSessions.values.forEach(::sendRefreshControl)
                        },
                        onSetNoiseMode = ::sendNoiseModeControl,
                        onDashboardVisibilityChanged = ::onDashboardVisibilityChanged,
                        settings = currentSettings,
                        autoCheckUpdates = automaticUpdates,
                        rootAvailable = hasRoot,
                        rootActionState = rootAction,
                        onSettingsChanged = ::updateSettings,
                        onAutoCheckUpdatesChanged = ::updateAutoCheckUpdates,
                        onRunRootAction = ::runRootAction,
                        onExportLogs = ::exportLogs,
                        updateCheckState = updateCheck,
                        onCheckUpdates = updateCheckCoordinator::checkManually,
                        onDismissUpdate = updateCheckCoordinator::dismissAvailableDialog,
                        onOpenRelease = ::openRelease,
                        uiPreferences = uiPreferences,
                        onUiPreferencesChanged = uiPreferencesStore::update,
                    )
                }
            }
        }
        activityScope.launch {
            rootAvailable.value = RootCommandRunner.isAvailable()
        }
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        (application as HyperEarsApplication).addPreferencesListener(modulePreferencesListener)
        runtimeResponsive.value = false
        miLinkProcesses.value = emptySet()
        requestRuntimeState()
        if (dashboardVisible) beginDashboardRefresh()
        if (autoCheckUpdates.value) updateCheckCoordinator.checkAutomatically()
    }

    override fun onDestroy() {
        detachRemotePreferences()
        runCatching { unregisterReceiver(receiver) }
        activityScope.cancel()
        super.onDestroy()
    }

    private fun requestRuntimeState() {
        sendBroadcast(
            ModuleContract.requestState(packageName)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
        )
        sendBroadcast(
            ModuleContract.requestBridgeStatus(packageName)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
        )
    }

    private fun sendRefreshControl(session: DeviceSessionSnapshot) {
        sendRefreshControl(session.state, session.sessionToken)
    }

    private fun sendRefreshControl(
        state: EarbudState,
        sessionToken: String,
    ) {
        val address = state.address ?: return
        if (!state.connected) return
        sendBroadcast(
            ModuleContract.control(StandardControlRequest.Refresh, address, sessionToken)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
        )
    }

    private fun sendNoiseModeControl(
        address: String,
        sessionToken: String,
        mode: NoiseMode,
    ) {
        val session = sessionCollection.value.sessions.values.firstOrNull { snapshot ->
            snapshot.sessionToken == sessionToken &&
                snapshot.state.address.equals(address, ignoreCase = true)
        } ?: return
        val state = session.state
        val adapter = state.adapter ?: return
        if (
            !state.connected ||
            state.lifecycle.controlOwnership != ControlOwnership.MODULE ||
            !adapter.capabilities.noiseControl ||
            mode !in adapter.supportedNoiseModes
        ) {
            return
        }
        sendBroadcast(
            ModuleContract.control(
                StandardControlRequest.SetNoiseMode(mode),
                address,
                sessionToken,
            ).addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
        )
    }

    private fun onDashboardVisibilityChanged(visible: Boolean) {
        if (dashboardVisible == visible) return
        dashboardVisible = visible
        if (visible && activityStarted) {
            requestRuntimeState()
            beginDashboardRefresh()
        } else if (!visible) {
            dashboardRefreshedSessionTokens.clear()
        }
    }

    private fun beginDashboardRefresh() {
        dashboardRefreshedSessionTokens.clear()
        sessionCollection.value.sessions.values.forEach(::refreshDashboardSessionIfNeeded)
    }

    private fun refreshDashboardSessionIfNeeded(session: DeviceSessionSnapshot) {
        refreshDashboardSessionIfNeeded(session.state, session.sessionToken)
    }

    private fun refreshDashboardSessionIfNeeded(
        state: EarbudState,
        sessionToken: String,
    ) {
        if (!activityStarted || !dashboardVisible || !state.connected) return
        if (!dashboardRefreshedSessionTokens.add(sessionToken)) return
        sendRefreshControl(state, sessionToken)
    }

    private fun updateSettings(updated: ModuleSettings) {
        val previous = settings.value
        if (previous == updated) return
        val preferences = remotePreferences
        if (preferences == null) {
            if (!ModuleSettingsStore.writeLocal(this, updated, pendingRemoteWrite = true)) return
            settings.value = updated
            recordSettingsChange(previous, updated)
            return
        }
        if (!ModuleSettingsStore.write(preferences, updated)) return
        ModuleSettingsStore.writeLocal(this, updated, pendingRemoteWrite = false)
        settings.value = updated
        recordSettingsChange(previous, updated)
    }

    override fun onStop() {
        activityStarted = false
        dashboardRefreshedSessionTokens.clear()
        (application as HyperEarsApplication).removePreferencesListener(modulePreferencesListener)
        super.onStop()
    }

    private fun bindRemotePreferences(preferences: SharedPreferences?) {
        if (remotePreferences === preferences) return
        detachRemotePreferences()
        remotePreferences = preferences
        if (preferences == null) {
            settings.value = ModuleSettingsStore.readLocal(this)
            return
        }
        settings.value = ModuleSettingsStore.synchronizeRemote(this, preferences)
        preferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    private fun detachRemotePreferences() {
        remotePreferences?.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        remotePreferences = null
    }

    private fun runRootAction(action: RootAction) {
        if (rootAvailable.value != true) return
        rootActionState.value = RootActionState.Running(action)
        activityScope.launch {
            val result = RootCommandRunner.run(action)
            rootActionState.value = result
            AppDiagnosticLog.record(
                context = this@MainActivity,
                enabled = settings.value.diagnosticLogging,
                component = "QuickControl",
                message = buildString {
                    append(action.title)
                    append(" · success=")
                    append(result.success)
                    append('\n')
                    append(result.detail)
                },
            )
        }
    }

    private fun exportLogs() {
        if (rootAvailable.value != true) return
        createDiagnosticDocument.launch(DiagnosticLogExporter.defaultFileName())
    }

    private fun updateAutoCheckUpdates(enabled: Boolean) {
        if (autoCheckUpdates.value == enabled) return
        updateCheckPreferences.automaticChecksEnabled = enabled
        autoCheckUpdates.value = enabled
        if (enabled) updateCheckCoordinator.checkAutomatically()
        activityScope.launch {
            AppDiagnosticLog.record(
                context = this@MainActivity,
                enabled = settings.value.diagnosticLogging,
                component = "Settings",
                message = "autoCheckUpdates=$enabled",
            )
        }
    }

    private fun openRelease(release: ReleaseInfo) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, release.pageUrl.toUri()),
            )
        }.onFailure {
            Toast.makeText(this, "无法打开 Release 页面", Toast.LENGTH_SHORT).show()
        }
        updateCheckCoordinator.dismissAvailableDialog()
    }

    private fun recordSettingsChange(
        previous: ModuleSettings,
        updated: ModuleSettings,
    ) {
        if (!updated.diagnosticLogging) return
        activityScope.launch {
            AppDiagnosticLog.record(
                context = this@MainActivity,
                enabled = true,
                component = "Settings",
                message = buildList {
                    if (previous.modulePaused != updated.modulePaused) {
                        add("modulePaused=${updated.modulePaused}")
                    }
                    if (previous.moreSettingsTarget != updated.moreSettingsTarget) {
                        add("moreSettingsTarget=${updated.moreSettingsTarget.name}")
                    }
                    if (previous.yieldToVendorControlApp != updated.yieldToVendorControlApp) {
                        add("yieldToVendorControlApp=${updated.yieldToVendorControlApp}")
                    }
                    if (previous.diagnosticLogging != updated.diagnosticLogging) {
                        add("diagnosticLogging=${updated.diagnosticLogging}")
                    }
                    if (previous.disabledAdapterIds != updated.disabledAdapterIds) {
                        add(
                            "disabledAdapters=" +
                                updated.disabledAdapterIds.sorted().joinToString(","),
                        )
                    }
                }.joinToString(separator = " · "),
            )
        }
    }
}
