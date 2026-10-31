package dev.hyperears.bridge

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import dev.hyperears.integration.ControlAppSpec
import dev.hyperears.integration.ControlRequest
import dev.hyperears.integration.EarbudState

/** Launches only after the Bluetooth owner has acknowledged releasing its private channel. */
internal object VendorHandoffLauncher {
    private val leases = VendorHandoffReturnLeases()

    fun open(
        context: Context,
        address: String,
        candidates: List<ControlAppSpec>,
        stateProvider: (String) -> EarbudState,
        sessionTokenProvider: (String) -> String?,
        observeState: (String, (EarbudState) -> Unit) -> (() -> Unit),
        controlSender: (String, ControlRequest) -> Unit,
    ): Boolean {
        val (app, intent) = candidates.firstNotNullOfOrNull { app ->
            context.packageManager.getLaunchIntentForPackage(app.packageName)?.let { app to it }
        } ?: return false
        val handler = Handler(Looper.getMainLooper())
        return VendorHandoffCoordinator(
            address, app, stateProvider, sessionTokenProvider, observeState,
            schedule = { timeout ->
                val runnable = Runnable(timeout)
                handler.postDelayed(runnable, 5_000L)
                val cancel: () -> Unit = { handler.removeCallbacks(runnable) }
                cancel
            },
            send = controlSender,
            launch = {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                true
            },
            onLaunched = { token, leaseId ->
                leases.record(address, token, leaseId)
            },
        ).start()
    }

    fun hasReturnableLease(address: String, sessionToken: String?): Boolean = leases.contains(address, sessionToken)

    /** Explicit return respects the user's global automatic-yield policy in the session owner. */
    fun returnControl(
        address: String,
        sessionTokenProvider: (String) -> String?,
        controlSender: (String, ControlRequest) -> Unit,
    ): Boolean = leases.returnControl(address, sessionTokenProvider, controlSender)
}
