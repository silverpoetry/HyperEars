package dev.hyperears.integration

/**
 * Bounded first-report policy for ZZZ-ANGELS-OWS. Existing models keep their own policies.
 *
 * Hold provisional reports until both sides are known or the bounded bootstrap is exhausted.
 * After the first accepted report, explicit unknown readings clear stale components immediately.
 * One adapter instance owns this state; the Android runtime only schedules the declared reads.
 */
internal class MoondropZzzAngelsBatteryPolicy {
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
