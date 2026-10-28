package dev.hyperears.hook

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.hyperears.presentation.NativePresentationRuntime

/** Native Bluetooth host bootstrap, including its event-driven Qigsaw plugin loader. */
internal class XiaomiBluetoothProcessHook : HookContext() {
    private var runtime: NativePresentationRuntime? = null
    private var admissionStarted = false
    private var discoveryStarted = false
    private val main = Handler(Looper.getMainLooper())

    override fun install() {
        hookAfter(findMethod("com.android.bluetooth.ble.MiuiApplication", "onCreate")) {
            val context = instance as? Context ?: return@hookAfter
            ready(context)
        }
        runCatching {
            hookAfter(findMethod("com.xiaomi.bluetooth.qigsaw.reporter.SampleSplitLoadReporter",
                "onLoadOK", String::class.java, List::class.java, Long::class.javaPrimitiveType!!)) {
                val context = getObjectField(instance, "mContext") as? Context ?: return@hookAfter
                main.post { ready(context) }
            }
        }.onFailure { ModuleLog.debug("NativePresentation", "optional plugin load reporter absent") }
    }

    private fun ready(context: Context) {
        val app = context.applicationContext ?: context
        val process = Application.getProcessName()
        if (runtime == null) runtime = NativePresentationRuntime(app,
            uiProcess = process == "$packageName:ui",
            configure = { bridge, loader ->
                bridge.module = module
                bridge.appClassLoader = loader
                bridge.packageName = packageName
            })
        runtime?.pluginReady(app.classLoader)
        if (process != "$packageName:ui" && !discoveryStarted) {
            discoveryStarted = true
            runCatching {
                AppleNativeScanObservationHook(app).also {
                    it.module = module
                    it.appClassLoader = app.classLoader
                    it.packageName = packageName
                    it.install()
                }
            }.onFailure { ModuleLog.warn("AppleDiscovery", "native scan observation unavailable", it) }
        }
        if (process != "$packageName:ui" && !admissionStarted && runCatching {
                Class.forName(AppleNativeConnectionResolver.STORAGE, false, app.classLoader)
            }.isSuccess) {
            admissionStarted = true
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            executor.execute {
                val resolved = runCatching {
                    AppleNativeConnectionResolver.resolve(app.applicationInfo.sourceDir, app.classLoader)
                }
                main.post {
                    resolved.onSuccess { method -> runCatching {
                        AppleNativeConnectionHook(app, method).also {
                            it.module = module
                            it.appClassLoader = app.classLoader
                            it.packageName = packageName
                            it.install()
                        }
                    }.onFailure { ModuleLog.warn("AppleNativeAdmission", "observer unavailable", it) } }
                        .onFailure { ModuleLog.warn("AppleNativeAdmission", "native connection fingerprint unavailable", it) }
                }
                executor.shutdown()
            }
        }
    }
}
