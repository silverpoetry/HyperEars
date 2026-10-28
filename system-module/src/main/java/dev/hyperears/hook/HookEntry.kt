package dev.hyperears.hook

import android.app.Application
import dev.hyperears.BuildConfig
import dev.hyperears.bridge.ModuleRuntimeGate
import dev.hyperears.integration.ControlAppCatalog
import dev.hyperears.settings.ModuleSettingsRuntime
import dev.hyperears.settings.ModuleSettingsStore
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

class HookEntry : XposedModule() {
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage) return
        ModuleLog.module = this
        runCatching {
            getRemotePreferences(ModuleSettingsStore.PREFERENCES_GROUP)
        }.onSuccess { preferences ->
            ModuleSettingsRuntime.bind(preferences)
            ModuleSettingsRuntime.observe { settings ->
                ModuleRuntimeGate.update(settings.modulePaused)
            }
        }
        ModuleLog.debug(
            "Entry",
            "package loaded version=${BuildConfig.VERSION_NAME} package=${param.packageName} " +
                "classLoader=${param.defaultClassLoader.javaClass.name}",
        )

        val hooks = when (param.packageName) {
            "com.android.bluetooth" -> listOf(BluetoothProcessHook())
            "com.android.settings" -> if (Application.getProcessName() == "com.android.settings") listOf(SettingsHeadsetHook()) else emptyList()
            "com.xiaomi.bluetooth" -> if (Application.getProcessName() in
                setOf("com.xiaomi.bluetooth", "com.xiaomi.bluetooth:ui")) {
                listOf(XiaomiBluetoothProcessHook())
            } else emptyList()

            "com.milink.service" -> {
                val processName = Application.getProcessName()
                if (processName in MILINK_PROCESSES) {
                    listOf(MiLinkServiceHook())
                } else {
                    ModuleLog.debug("Entry", "ignored MiLink process=$processName")
                    emptyList()
                }
            }

            else -> ControlAppCatalog.find(param.packageName)
                ?.let { listOf(ControlAppProcessHook(it)) }
                .orEmpty()
        }
        ModuleLog.debug("Entry", "selected hooks=${hooks.map { it.javaClass.simpleName }} package=${param.packageName}")

        hooks.forEach { hook ->
            hook.module = this
            hook.appClassLoader = param.defaultClassLoader
            hook.packageName = param.packageName
            runCatching(hook::install)
                .onSuccess {
                    ModuleLog.debug(
                        "Entry",
                        "installed ${hook.javaClass.simpleName} in ${param.packageName}",
                    )
                }
                .onFailure {
                    ModuleLog.warn(
                        "Entry",
                        "failed to install ${hook.javaClass.simpleName} in ${param.packageName}",
                        it,
                    )
            }
        }
    }

    private companion object {
        val MILINK_PROCESSES = setOf(
            "com.milink.service:audio",
            "com.milink.service:core",
            "com.milink.service:ui",
        )
    }
}
