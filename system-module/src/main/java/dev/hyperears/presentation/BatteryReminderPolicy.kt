package dev.hyperears.presentation

import dev.hyperears.integration.EarbudBattery

/** One user-facing case-battery alarm produced by [BatteryReminderPolicy]. */
data class BatteryReminder(val casePercent: Int)

/**
 * One-shot suppression token: armed by an alarm, consumed by the first repeat it suppresses.
 * Token ownership follows the suppressed side — the counterpart's alarm arms it, the
 * suppressed alarm consumes it, and the next counterpart alarm re-arms it.
 */
private class SuppressionToken {
    var available = false

    fun consume(): Boolean =
        if (available) {
            available = false
            true
        } else {
            false
        }

    fun arm() {
        available = true
    }
}

/**
 * Pure, node-driven low-case-battery alarm decisions with one-shot repeat suppression.
 *
 * Channel A covers the connection moments: the first case reading of a session (the lid was
 * just opened, or the buds docked into an already-connected case) and the session end when the
 * lid closes. Channel B covers the handling moments inside a session: the reading vanishing
 * (the buds were taken out, judged by the last known level) and reappearing (the buds docked
 * back). An alarm suppresses the first alarm of its counterpart for [crossSuppressionMillis] —
 * the other channel across channels, and the matching connection moment within channel A — one
 * suppression only, so the immediate repeat of a single open/close or take-out/lid-close cycle
 * stays quiet while later actions recover. Handling nodes closer together than
 * [replacementGapMillis] count as one gesture and stay silent, and readings that persist
 * without appearing or vanishing never warn on their own. Channel state survives across
 * sessions; [onSessionEnded] retires only the session-scoped memory.
 *
 * All state is unsynchronized by design: battery updates and vanish confirmations must run
 * on the same thread (the caller's main thread), which the notification layer guarantees.
 *
 * A vanishing reading is ambiguous: the buds may have been taken out, or the lid may have just
 * closed (the buds power down and the reading disappears right before the session ends). The
 * vanish therefore only arms a pending take-out — [confirmVanish] fires it after the caller's
 * grace delay once the session proved it survived, while [onSessionEnded] drops the pending
 * vanish and lets the disconnect node speak for the lid close.
 */
class BatteryReminderPolicy(
    private val lowCasePercent: Int = DEFAULT_LOW_CASE_PERCENT,
    private val connectionAlarmsEnabled: Boolean = true,
    private val handlingAlarmsEnabled: Boolean = true,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val crossSuppressionMillis: Long = DEFAULT_CROSS_SUPPRESSION_MILLIS,
    private val replacementGapMillis: Long = DEFAULT_REPLACEMENT_GAP_MILLIS,
) {
    init {
        require(lowCasePercent in 0..100)
        require(crossSuppressionMillis >= 0)
        require(replacementGapMillis >= 0)
    }

    /** null until the first battery update; afterwards mirrors whether the reading exists. */
    private var casePresent: Boolean? = null

    /** Whether this session ever produced a reading; separates connection nodes from handling. */
    private var sessionSawCase = false
    private var lastCasePercent: Int? = null

    /**
     * A vanishing reading awaits confirmation that the session survived; null once confirmed,
     * refuted by a reappearing reading or retired by the session end.
     */
    private var pendingVanish = false

    /** Channel state below survives sessions so suppression carries over reconnects. */
    private var lastConnectionAlarmMillis: Long? = null
    private var lastConnectAlarmMillis: Long? = null
    private var lastDisconnectAlarmMillis: Long? = null
    private var lastHandlingAlarmMillis: Long? = null
    private val connectionToken = SuppressionToken()
    private val handlingToken = SuppressionToken()
    private val connectToken = SuppressionToken()
    private val disconnectToken = SuppressionToken()

    /** When the reading last vanished; anchors the one-gesture window on the physical event. */
    private var lastVanishMillis: Long? = null

    /** Consumes one standardized battery update from an active session. */
    fun onBatteryUpdate(battery: EarbudBattery): List<BatteryReminder> {
        val fresh = battery.case.percent
        val alarms = mutableListOf<BatteryReminder>()
        when {
            // Connection node: the session's first reading, reported on channel A.
            fresh != null && !sessionSawCase -> {
                sessionSawCase = true
                if (connectionAlarmsEnabled) {
                    fresh.takeIf { it <= lowCasePercent }?.let {
                        connectAlarm(it)?.let(alarms::add)
                    }
                }
            }
            // Put-back node: the reading reappears mid-session, reported on channel B. A
            // pending vanish refuted by this reappear was a single-frame dropout, not a
            // take-out, so it is dropped without ever warning.
            fresh != null && casePresent != true -> {
                pendingVanish = false
                if (handlingAlarmsEnabled && !sameGestureAsLastVanish()) {
                    fresh.takeIf { it <= lowCasePercent }?.let {
                        handlingAlarm(it)?.let(alarms::add)
                    }
                }
            }
            // Take-out node: the reading vanished. Whether the buds left the case or the lid
            // just closed cannot be told apart from this frame alone, so nothing fires here;
            // [confirmVanish] decides after the caller's grace delay.
            fresh == null && casePresent == true -> {
                lastVanishMillis = nowMillis()
                pendingVanish = handlingAlarmsEnabled
            }
        }
        casePresent = fresh != null
        fresh?.let { lastCasePercent = it }
        return alarms
    }

    /** Whether a vanishing reading awaits its confirmation. */
    fun hasPendingVanish(): Boolean = pendingVanish

    /**
     * Fires the pending take-out alarm once the caller's grace delay proved the session
     * survived the vanish. Returns null when the vanish was already refuted or retired, or
     * when the take-out lands inside the same gesture as the previous handling alarm.
     */
    fun confirmVanish(): BatteryReminder? {
        if (!pendingVanish) return null
        pendingVanish = false
        if (!handlingAlarmsEnabled || sameGestureAsLastAlarm()) return null
        return lastCasePercent
            ?.takeIf { it <= lowCasePercent }
            ?.let { handlingAlarm(it) }
    }

    /** Emits the channel-A disconnect alarm and retires session memory, keeping channel state. */
    fun onSessionEnded(): List<BatteryReminder> {
        // A vanish still pending at this point was the lid closing, not a take-out.
        pendingVanish = false
        val reminder = if (connectionAlarmsEnabled) {
            lastCasePercent
                ?.takeIf { it <= lowCasePercent }
                ?.let { disconnectAlarm(it) }
                ?.let { listOf(it) }
                ?: emptyList()
        } else {
            emptyList()
        }
        casePresent = null
        lastCasePercent = null
        sessionSawCase = false
        return reminder
    }

    /** A put-back this soon after the vanish counts as the same take-out gesture. */
    private fun sameGestureAsLastVanish(): Boolean =
        lastVanishMillis?.let { nowMillis() - it < replacementGapMillis } == true

    /** A take-out confirmed this soon after a handling alarm counts as the same gesture. */
    private fun sameGestureAsLastAlarm(): Boolean =
        lastHandlingAlarmMillis?.let { nowMillis() - it < replacementGapMillis } == true

    /**
     * Channel B gate: the first alarm within the suppression window after a connection alarm
     * is dropped, and any alarm that fires arms the connection-side suppression in return.
     */
    private fun handlingAlarm(percent: Int): BatteryReminder? {
        if (suppressOnce(connectionAlarmRecent(), handlingToken)) return null
        lastHandlingAlarmMillis = nowMillis()
        connectionToken.arm()
        return BatteryReminder(percent)
    }

    /**
     * Channel A session-start gate: the first alarm within the suppression window after a
     * handling or disconnect alarm is dropped, and any alarm that fires arms the handling-side
     * and disconnect-side suppression in return.
     */
    private fun connectAlarm(percent: Int): BatteryReminder? {
        if (suppressOnce(handlingAlarmRecent(), connectionToken)) return null
        if (suppressOnce(disconnectAlarmRecent(), connectToken)) return null
        lastConnectionAlarmMillis = nowMillis()
        lastConnectAlarmMillis = nowMillis()
        handlingToken.arm()
        disconnectToken.arm()
        return BatteryReminder(percent)
    }

    /**
     * Channel A session-end gate: the first alarm within the suppression window after a
     * handling or connect alarm is dropped, and any alarm that fires arms the handling-side
     * and connect-side suppression in return.
     */
    private fun disconnectAlarm(percent: Int): BatteryReminder? {
        if (suppressOnce(handlingAlarmRecent(), connectionToken)) return null
        if (suppressOnce(connectAlarmRecent(), disconnectToken)) return null
        lastConnectionAlarmMillis = nowMillis()
        lastDisconnectAlarmMillis = nowMillis()
        handlingToken.arm()
        connectToken.arm()
        return BatteryReminder(percent)
    }

    /** Drops an alarm when its anchor alarm is recent and the one-shot token is still armed. */
    private fun suppressOnce(anchorRecent: Boolean, token: SuppressionToken): Boolean =
        anchorRecent && token.consume()

    private fun connectionAlarmRecent(): Boolean =
        lastConnectionAlarmMillis
            ?.let { nowMillis() - it < crossSuppressionMillis } == true

    private fun connectAlarmRecent(): Boolean =
        lastConnectAlarmMillis
            ?.let { nowMillis() - it < crossSuppressionMillis } == true

    private fun disconnectAlarmRecent(): Boolean =
        lastDisconnectAlarmMillis
            ?.let { nowMillis() - it < crossSuppressionMillis } == true

    private fun handlingAlarmRecent(): Boolean =
        lastHandlingAlarmMillis
            ?.let { nowMillis() - it < crossSuppressionMillis } == true

    companion object {
        const val DEFAULT_LOW_CASE_PERCENT = 30

        /** How long an alarm suppresses the first alarm of its counterpart node. */
        const val DEFAULT_CROSS_SUPPRESSION_MILLIS = 30_000L

        /** Handling nodes this close together count as one take-out/put-back gesture. */
        const val DEFAULT_REPLACEMENT_GAP_MILLIS = 6_000L

        /**
         * How long a vanishing reading waits for the session end before the take-out alarm is
         * confirmed; a lid close ends the session well within this window and never warns.
         */
        const val DEFAULT_VANISH_GRACE_MILLIS = 1_000L
    }
}
