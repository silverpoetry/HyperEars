package dev.hyperears.presentation

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import dev.hyperears.integration.AppleSettingsFeatureState
import dev.hyperears.integration.AppleWearFeatureState
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.ControlOwnership

/** Runs only in the native Bluetooth host, downstream of authenticated producer/session admission. */
internal class AppleWearRuntime(private val coordinator: AppleWearCoordinator) {
    constructor(context: Context, handler: Handler) : this(createCoordinator(context, handler))

    fun update(state: EarbudState, session: String, admission: NativeAdmission, enabled: Boolean) {
        val address = state.address ?: return
        val wear = state.features.get<AppleWearFeatureState>()
        if (!enabled || admission != NativeAdmission.HYPEREARS || !state.sessionActive ||
            !state.privateChannelConnected || !state.handshakeAccepted ||
            state.lifecycle.controlOwnership != ControlOwnership.MODULE ||
            state.features.get<AppleSettingsFeatureState>()?.wearingDetection != true || wear == null) {
            remove(address); return
        }
        coordinator.update(address, session, wear.inEarCount)
    }

    fun remove(address: String) = coordinator.remove(address)
    fun clear() = coordinator.clear()

    companion object {
        private fun createCoordinator(context: Context, handler: Handler): AppleWearCoordinator {
            var timer: Runnable? = null
            lateinit var coordinator: AppleWearCoordinator
            coordinator = AppleWearCoordinator(AppleWearMediaHost(context, handler), SystemClock::elapsedRealtime) { deadline ->
                timer?.let(handler::removeCallbacks)
                timer = deadline?.let {
                    Runnable { timer = null; coordinator.refresh() }.also { task ->
                        handler.postDelayed(task, (it - SystemClock.elapsedRealtime()).coerceAtLeast(1))
                    }
                }
            }
            return coordinator
        }
    }
}
