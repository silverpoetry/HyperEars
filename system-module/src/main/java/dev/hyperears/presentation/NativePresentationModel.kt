package dev.hyperears.presentation

import dev.hyperears.integration.BatteryReading
import dev.hyperears.integration.BatterySource
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.HeadsetArtwork
import dev.hyperears.integration.HeadsetFormFactor
import dev.hyperears.settings.HeadsetConnectionPresentation

/** Physical connection provenance is separate from a replaceable protocol session. */
data class HeadsetConnection(
    val id: String,
    val connectedAtElapsedMs: Long,
)

enum class NativeAdmission { UNKNOWN, HYPEREARS, SYSTEM }

data class NativePresentationMetadata(
    val connection: HeadsetConnection,
    val admission: NativeAdmission = NativeAdmission.UNKNOWN,
    val replay: Boolean = false,
)

/** A read-only projection. Aggregate values copied into MiLink slots are not component telemetry. */
data class NativeHeadsetProjection(
    val address: String,
    val name: String,
    val form: HeadsetFormFactor,
    val artwork: HeadsetArtwork,
    val singleBattery: Boolean,
    val left: BatteryReading,
    val right: BatteryReading,
    val chargingCase: BatteryReading,
) {
    val hasBattery: Boolean get() = left.available || right.available || chargingCase.available

    val showCaseInPopup: Boolean
        get() = form == HeadsetFormFactor.TWS && !singleBattery && chargingCase.available

    // The native connected island needs ear/headphone telemetry. Its single-battery template
    // becomes an invalid icon-only model before a reading exists; a case reading cannot fill it.
    val canShowConnectionHint: Boolean get() = left.available || right.available

    /** The native one-value island may use either its headphone or generic earbud artwork. */
    val singleBatteryHintArtwork: String
        get() = if (form == HeadsetFormFactor.TWS) "earphone" else "single_battery_head"

    companion object {
        fun from(state: EarbudState): NativeHeadsetProjection? {
            val adapter = state.adapter ?: return null
            val address = state.address ?: return null
            if (!state.sessionActive) return null
            val battery = state.battery
            val single = adapter.batterySource == BatterySource.SYSTEM_AGGREGATE ||
                battery.overall.available || adapter.formFactor == HeadsetFormFactor.HEADPHONES
            val unknown = BatteryReading(null, false)
            return NativeHeadsetProjection(
                address = address,
                name = state.deviceName?.takeIf(String::isNotBlank) ?: adapter.displayName,
                form = adapter.formFactor,
                artwork = adapter.artwork,
                singleBattery = single,
                left = if (single) battery.overall else battery.left,
                right = if (single) unknown else battery.right,
                chargingCase = if (single) unknown else battery.case,
            )
        }
    }
}

internal enum class NativePresentationTarget { POPUP, ISLAND }

/** Unknown native scene information cannot authorize an automatic popup. */
internal fun resolveNativePresentationTarget(
    selection: HeadsetConnectionPresentation,
    onHomeOrCoverScreen: Boolean?,
): NativePresentationTarget? = when (selection) {
    HeadsetConnectionPresentation.SMART -> when (onHomeOrCoverScreen) {
        true -> NativePresentationTarget.POPUP
        false -> NativePresentationTarget.ISLAND
        null -> null
    }
    HeadsetConnectionPresentation.BATTERY_POPUP -> NativePresentationTarget.POPUP
    HeadsetConnectionPresentation.SYSTEM_HINT -> NativePresentationTarget.ISLAND
    HeadsetConnectionPresentation.OFF -> null
}

/**
 * Whether the native circulation flow currently owns the earbud display and the module must stand down.
 * A null reading means the native hint subsystem could not be installed at all, so circulation is neither
 * observable nor provable: that alone must not keep the popup closed, while the native window occupancy
 * guard and the connection gate still decide. A present subsystem keeps its exact verdict. An absent
 * singleton (the subsystem is installed but the native instance is not created yet) is reported by the
 * query as true and does block: that is a different kind of indeterminacy from a missing subsystem and
 * the two must not be merged.
 */
internal fun displayBlockedByNativeCirculation(flowOwns: Boolean?): Boolean = flowOwns == true

/**
 * The circulation fallback chain (island-spec §8): verified legacy record getters decide on their own
 * and keep the old-build verdict unchanged; only when this build lacks those names the stable wrapper
 * readings decide; neither being readable is indeterminate, which [displayBlockedByNativeCirculation]
 * never reads as a block. A null pair/triple means that probe could not run, and inner nulls mean an
 * unreadable single flag.
 */
internal fun flowOwnsFromReadings(
    legacy: Triple<Int?, Boolean?, Boolean?>?,
    wrappers: Pair<Boolean?, Boolean?>?,
): Boolean? = when {
    legacy != null -> legacy.first == 1 || legacy.second == true || legacy.third == true
    wrappers != null -> wrappers.first == true || wrappers.second == true
    else -> null
}

/**
 * The popup's charging-case area on contract-absent builds: the host window layout generation there
 * has no third-party case artwork, so showing it produced a blank block and stacked buttons (device
 * 2026-10-31 01:03). The area keeps the explicit hidden shape on those builds, and every other build
 * applies the projection's own verdict unchanged.
 */
internal fun caseAreaEffective(contractMissing: Boolean, showCase: Boolean): Boolean =
    if (contractMissing) false else showCase

/** No timers or IO; failed eligibility/display checks do not consume a connection. */
internal class ConnectionPresentationGate(private val freshnessMs: Long = 10_000) {
    private val consumed = mutableMapOf<String, Long>()
    private val pending = mutableMapOf<String, Long>()

    fun tryPresent(
        metadata: NativePresentationMetadata,
        now: Long,
        onDecision: (String) -> Unit = {},
        submit: () -> Boolean,
    ): Boolean {
        fun skip(reason: String): Boolean { onDecision(reason); return false }
        consumed.entries.removeAll { now - it.value > freshnessMs }
        pending.entries.removeAll { now - it.value > freshnessMs }
        if (metadata.replay) {
            // A startup replay cannot itself open a window. It must not cancel a live connection
            // already waiting for native admission, plugin readiness or battery telemetry either.
            if (metadata.connection.id !in pending) {
                consumed[metadata.connection.id] = metadata.connection.connectedAtElapsedMs
            }
            return skip("replay")
        }
        if (now - metadata.connection.connectedAtElapsedMs !in 0..freshnessMs) return skip("expired")
        if (metadata.connection.id in consumed) return skip("consumed")
        pending[metadata.connection.id] = metadata.connection.connectedAtElapsedMs
        if (metadata.admission != NativeAdmission.HYPEREARS) return skip("admission-${metadata.admission}")
        if (!submit()) return skip("not-submitted")
        pending.remove(metadata.connection.id)
        consumed[metadata.connection.id] = metadata.connection.connectedAtElapsedMs
        onDecision("submitted")
        return true
    }

    fun retire(id: String) { consumed.remove(id); pending.remove(id) }
    fun clear() { consumed.clear(); pending.clear() }
}
