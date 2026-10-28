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
    /** This connection's transport owner was decided by the native Apple connection gate. */
    val connectionScopedOwnership: Boolean = false,
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
    val canShowConnectionHint: Boolean
        get() = (left.available || right.available) &&
            !(singleBattery && form == HeadsetFormFactor.TWS)

    companion object {
        fun from(state: EarbudState): NativeHeadsetProjection? =
            if (state.sessionActive) project(state) else null

        /** Only the authenticated paired-discovery presentation path may use this projection. */
        fun fromPairedDiscovery(state: EarbudState): NativeHeadsetProjection? =
            if (!state.sessionActive) project(state) else null

        private fun project(state: EarbudState): NativeHeadsetProjection? {
            val adapter = state.adapter ?: return null
            val address = state.address ?: return null
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

/** No timers or IO; failed eligibility/display checks do not consume a connection. */
internal class ConnectionPresentationGate(private val freshnessMs: Long = 10_000) {
    private val consumed = mutableMapOf<String, Long>()

    fun tryPresent(metadata: NativePresentationMetadata, now: Long, submit: () -> Boolean): Boolean {
        consumed.entries.removeAll { now - it.value > freshnessMs }
        if (metadata.replay) {
            consumed[metadata.connection.id] = metadata.connection.connectedAtElapsedMs
            return false
        }
        if (metadata.admission != NativeAdmission.HYPEREARS) return false
        if (now - metadata.connection.connectedAtElapsedMs !in 0..freshnessMs) return false
        if (metadata.connection.id in consumed || !submit()) return false
        consumed[metadata.connection.id] = metadata.connection.connectedAtElapsedMs
        return true
    }

    fun retire(id: String) { consumed.remove(id) }
    fun clear() { consumed.clear() }
}
