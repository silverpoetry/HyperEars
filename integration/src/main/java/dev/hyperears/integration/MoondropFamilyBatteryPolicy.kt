package dev.hyperears.integration

/**
 * Bounded battery-report policy for the MOONDROP family's two-component reports.
 *
 * The ZZZ-ANGELS-OWS and Pudding report the two earbuds as separate components and may answer
 * a one-side report while the other bud is still connecting. The policy holds such provisional
 * reports until both sides are known or a bounded bootstrap sequence is exhausted, so a
 * single-side connect never publishes a half-empty reading. State is owned by one adapter
 * instance; the Android runtime only schedules the declared follow-up reads.
 */
internal class MoondropFamilyBatteryPolicy {
    private var bootstrapAttempt = 0
    private var committed = false

    fun decide(
        battery: EarbudBattery,
        scope: AdapterEventScope,
    ): FeatureReportDecision {
        if (committed) return FeatureReportDecision.ACCEPT

        val provisional = battery.left.percent == null || battery.right.percent == null
        if (!provisional || bootstrapAttempt >= BOOTSTRAP_DELAYS_MS.size) {
            committed = true
            scope.cancelStateRequest(BatteryFeatureState.FEATURE_ID)
            return FeatureReportDecision.ACCEPT
        }

        scope.requestState(
            BatteryFeatureState.FEATURE_ID,
            BOOTSTRAP_DELAYS_MS[bootstrapAttempt++],
        )
        return FeatureReportDecision.HOLD
    }

    fun reset() {
        bootstrapAttempt = 0
        committed = false
    }

    companion object {
        internal val BOOTSTRAP_DELAYS_MS = longArrayOf(500L, 800L, 1_200L, 1_600L)
    }
}
