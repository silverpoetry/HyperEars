package dev.hyperears.hook

import android.app.Application
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import dev.hyperears.bridge.ModuleContract
import dev.hyperears.integration.ControlAppSpec

/** Installs the smallest possible presence hook in one vendor controller process. */
internal class ControlAppProcessHook(
    private val controlApp: ControlAppSpec,
) : HookContext() {
    @SuppressLint("DiscouragedPrivateApi") // Application.attach is the process-lifecycle hook boundary.
    override fun install() {
        val attach = Application::class.java.getDeclaredMethod(
            "attach",
            Context::class.java,
        ).apply { isAccessible = true }
        hookAfter(attach) {
            val context = args.singleOrNull() as? Context ?: return@hookAfter
            ControlAppProcessPresence.install(context, controlApp)
        }
        if (controlApp.packageName == SONY_SOUND_CONNECT_PACKAGE) {
            runCatching {
                ControlAppSocketTrace().also { trace ->
                    trace.module = module
                    trace.appClassLoader = appClassLoader
                    trace.packageName = packageName
                }.install()
            }.onFailure {
                ModuleLog.warn("ControlAppHook", "socket trace install failed", it)
            }
        }
    }

    private companion object {
        const val SONY_SOUND_CONNECT_PACKAGE = "com.sony.songpal.mdr"
    }
}

private object ControlAppProcessPresence {
    private val token = Binder()

    @Volatile
    private var installed = false

    fun install(context: Context, controlApp: ControlAppSpec) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val appContext = context.applicationContext ?: context
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        ModuleContract.ACTION_CONTROL_APP_QUERY -> announce(appContext, controlApp)
                    }
                }
            }
            appContext.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(ModuleContract.ACTION_CONTROL_APP_QUERY)
                },
                Context.RECEIVER_EXPORTED,
            )
            installed = true
            announce(appContext, controlApp)
            ModuleLog.debug(
                "ControlAppHook",
                "presence hook active for ${controlApp.packageName}",
            )
        }
    }

    private fun announce(context: Context, controlApp: ControlAppSpec) {
        val processName = Application.getProcessName()
        val intent = ModuleContract.controlAppRegistration(
            packageName = controlApp.packageName,
            processName = processName,
            token = token,
        ).addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        ModuleLog.debug(
            "ControlAppHook",
            "announcing package=${controlApp.packageName} process=$processName " +
                "tokenAlive=${token.isBinderAlive} tokenPresent=" +
                "${with(ModuleContract) { intent.controlAppRegistrationFields().tokenPresent }}",
        )
        runCatching {
            context.sendBroadcast(
                intent,
                null,
                ModuleContract.controlAppRegistrationOptions(),
            )
        }
            .onFailure { ModuleLog.warn("ControlAppHook", "registration broadcast failed", it) }
    }
}
