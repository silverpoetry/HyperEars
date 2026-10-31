package dev.hyperears.bridge

import dev.hyperears.integration.ControlAppSpec
import dev.hyperears.integration.ControlOwnership
import dev.hyperears.integration.ControlRequest
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.StandardControlRequest
import java.util.UUID

/** One bounded attempt, independent of the details window that requested it. */
internal class VendorHandoffCoordinator(
    private val address: String,
    private val app: ControlAppSpec,
    private val stateProvider: (String) -> EarbudState,
    private val tokenProvider: (String) -> String?,
    private val observe: (String, (EarbudState) -> Unit) -> (() -> Unit),
    private val schedule: (() -> Unit) -> (() -> Unit),
    private val send: (String, ControlRequest) -> Unit,
    private val launch: () -> Boolean,
    private val onLaunched: (String, String) -> Unit = { _, _ -> },
    private val leaseId: String = UUID.randomUUID().toString(),
) {
    private var token: String? = null
    private var baseline = 0L
    private var done = false
    private var armed = false
    private var unsubscribe: (() -> Unit)? = null
    private var cancelTimeout: (() -> Unit)? = null

    fun start(): Boolean {
        val state = stateProvider(address)
        if (!state.sessionActive || !state.address.equals(address, true) ||
            state.adapter?.controlApps?.none { it.packageName == app.packageName } != false) return false
        token = tokenProvider(address)?.takeIf { it.isNotBlank() } ?: return false
        baseline = state.revision
        return runCatching {
            val release = observe(address, ::accept)
            if (done) release() else unsubscribe = release
            val cancel = schedule { finish(false) }
            if (done) cancel() else cancelTimeout = cancel
            if (!done && tokenProvider(address) == token) {
                armed = true
                send(address, StandardControlRequest.BeginVendorHandoff(app.packageName, leaseId))
                accept(stateProvider(address))
            } else finish(false)
            !done || launched
        }.getOrElse { finish(false); false }
    }

    private var launched = false

    private fun accept(state: EarbudState) {
        if (done || !armed) return
        if (tokenProvider(address) != token || !state.sessionActive) {
            finish(false)
            return
        }
        if (!state.address.equals(address, true) || state.revision <= baseline ||
            state.lifecycle.controlOwnership != ControlOwnership.EXTERNAL_APP ||
            state.lifecycle.externalControlApp?.packageName != app.packageName) return
        // Unsubscribe before starting another Activity, which may synchronously dismiss our caller.
        done = true
        cleanup()
        launched = runCatching(launch).getOrDefault(false)
        if (launched) onLaunched(token!!, leaseId) else endLease()
    }

    private fun finish(success: Boolean) {
        if (done) return
        done = true
        cleanup()
        if (!success && armed) endLease()
    }

    private fun cleanup() {
        unsubscribe?.invoke()
        unsubscribe = null
        cancelTimeout?.invoke()
        cancelTimeout = null
    }

    private fun endLease() {
        if (tokenProvider(address) == token) {
            runCatching { send(address, StandardControlRequest.EndVendorHandoff(leaseId)) }
        }
    }
}
