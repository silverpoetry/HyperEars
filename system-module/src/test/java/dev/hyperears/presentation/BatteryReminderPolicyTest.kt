package dev.hyperears.presentation

import dev.hyperears.integration.BatteryReading
import dev.hyperears.integration.EarbudBattery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryReminderPolicyTest {
    private var now = 0L

    private fun policy(
        lowCasePercent: Int = BatteryReminderPolicy.DEFAULT_LOW_CASE_PERCENT,
        connectionAlarmsEnabled: Boolean = true,
        handlingAlarmsEnabled: Boolean = true,
    ) = BatteryReminderPolicy(
        lowCasePercent = lowCasePercent,
        connectionAlarmsEnabled = connectionAlarmsEnabled,
        handlingAlarmsEnabled = handlingAlarmsEnabled,
        nowMillis = { now },
    )

    private fun battery(casePercent: Int?) = EarbudBattery(
        left = BatteryReading(100, false),
        right = BatteryReading(100, false),
        case = BatteryReading(casePercent, false),
    )

    private fun advance(millis: Long) {
        now += millis
    }

    @Test
    fun firstSessionReadingBelowTheThresholdWarnsOnTheConnectionChannel() {
        val policy = policy()
        assertTrue(policy.onBatteryUpdate(battery(casePercent = null)).isEmpty())
        assertEquals(
            listOf(BatteryReminder(19)),
            policy.onBatteryUpdate(battery(casePercent = 19)),
        )
    }

    @Test
    fun healthyFirstSessionReadingStaysSilent() {
        val policy = policy()
        assertTrue(policy.onBatteryUpdate(battery(casePercent = 85)).isEmpty())
        advance(60_000)
        assertTrue(policy.onBatteryUpdate(battery(casePercent = 84)).isEmpty())
    }

    @Test
    fun theFirstHandlingAlarmAfterAConnectionAlarmIsSuppressed() {
        val policy = policy()
        assertEquals(
            listOf(BatteryReminder(19)),
            policy.onBatteryUpdate(battery(casePercent = 19)),
        )
        advance(2_000)
        // The take-out right after the connect alarm is the suppressed first repeat.
        assertTrue(policy.onBatteryUpdate(battery(casePercent = null)).isEmpty())
        assertTrue(policy.hasPendingVanish())
        advance(1_000)
        assertTrue(policy.confirmVanish() == null)
    }

    @Test
    fun handlingAlarmsAfterTheSuppressedOneWarnNormally() {
        val policy = policy()
        policy.onBatteryUpdate(battery(casePercent = 19))
        advance(2_000)
        assertTrue(policy.onBatteryUpdate(battery(casePercent = null)).isEmpty())
        advance(1_000)
        // Suppression is one-shot: the confirmed take-out consumed it.
        assertTrue(policy.confirmVanish() == null)
        advance(6_000)
        // The later put-back reports the level as a deliberate check.
        assertEquals(
            listOf(BatteryReminder(19)),
            policy.onBatteryUpdate(battery(casePercent = 19)),
        )
    }

    @Test
    fun theFirstConnectionAlarmAfterAHandlingAlarmIsSuppressed() {
        val policy = policy()
        // A healthy session start stays silent, so the handling alarm below fires unguarded.
        assertTrue(policy.onBatteryUpdate(battery(casePercent = 45)).isEmpty())
        advance(2_000)
        assertTrue(policy.onBatteryUpdate(battery(casePercent = null)).isEmpty())
        advance(31_000)
        // The level dropped below the threshold while worn: the put-back warns on channel B.
        assertEquals(
            listOf(BatteryReminder(19)),
            policy.onBatteryUpdate(battery(casePercent = 19)),
        )
        advance(2_000)
        // The disconnect right after the put-back alarm is the suppressed first repeat.
        assertTrue(policy.onSessionEnded().isEmpty())
    }

    @Test
    fun connectionAlarmsAfterTheSuppressedOneWarnNormally() {
        val policy = policy()
        assertTrue(policy.onBatteryUpdate(battery(casePercent = 45)).isEmpty())
        advance(31_000)
        assertTrue(policy.onBatteryUpdate(battery(casePercent = null)).isEmpty())
        advance(7_000)
        assertEquals(
            listOf(BatteryReminder(19)),
            policy.onBatteryUpdate(battery(casePercent = 19)),
        )
        advance(2_000)
        // The disconnect right after the put-back alarm is the suppressed first repeat.
        assertTrue(policy.onSessionEnded().isEmpty())
        // Suppression is one-shot: the next session's connect alarm fires again.
        policy.onBatteryUpdate(battery(casePercent = null))
        advance(3_000)
        assertEquals(
            listOf(BatteryReminder(19)),
            policy.onBatteryUpdate(battery(casePercent = 19)),
        )
    }

    @Test
    fun suppressionExpiresAfterTheWindow() {
        val policy = policy()
        assertEquals(
            listOf(BatteryReminder(19)),
            policy.onBatteryUpdate(battery(casePercent = 19)),
        )
        advance(31_000)
        // The suppression window has passed; the take-out warns through channel B.
        assertTrue(policy.onBatteryUpdate(battery(casePercent = null)).isEmpty())
        advance(1_000)
        assertEquals(BatteryReminder(19), policy.confirmVanish())
    }

    @Test
    fun theDisconnectAfterAConnectionAlarmIsSuppressedOnce() {
        val policy = policy()
        assertEquals(
            listOf(BatteryReminder(19)),
            policy.onBatteryUpdate(battery(casePercent = 19)),
        )
        advance(2_000)
        // The immediate lid close is the suppressed first repeat of the connect alarm: an
        // open-then-closed cycle reports once instead of twice.
        assertTrue(policy.onSessionEnded().isEmpty())
        // Suppression is one-shot: the next session's connect alarm fires again.
        policy.onBatteryUpdate(battery(casePercent = null))
        advance(3_000)
        assertEquals(
            listOf(BatteryReminder(19)),
            policy.onBatteryUpdate(battery(casePercent = 19)),
        )
    }

    @Test
    fun theDisconnectAfterTheSuppressionWindowWarnsNormally() {
        val policy = policy()
        assertEquals(
            listOf(BatteryReminder(19)),
            policy.onBatteryUpdate(battery(casePercent = 19)),
        )
        advance(31_000)
        // The lid stayed open past the window: the disconnect still reports the level.
        assertEquals(listOf(BatteryReminder(19)), policy.onSessionEnded())
    }

    @Test
    fun theConnectionAfterADisconnectAlarmIsSuppressedOnce() {
        val policy = policy()
        assertEquals(
            listOf(BatteryReminder(19)),
            policy.onBatteryUpdate(battery(casePercent = 19)),
        )
        advance(31_000)
        assertEquals(listOf(BatteryReminder(19)), policy.onSessionEnded())
        advance(2_000)
        // The quick reopen after the disconnect alarm is the suppressed first repeat.
        assertTrue(policy.onBatteryUpdate(battery(casePercent = 19)).isEmpty())
    }

    @Test
    fun aVanishAtTheLidCloseNeverWarnsAsATakeOut() {
        val policy = policy()
        assertEquals(
            listOf(BatteryReminder(19)),
            policy.onBatteryUpdate(battery(casePercent = 19)),
        )
        advance(2_000)
        // Take-out: confirmed suppressed by the connection alarm's one-shot token.
        assertTrue(policy.onBatteryUpdate(battery(casePercent = null)).isEmpty())
        advance(1_000)
        assertTrue(policy.confirmVanish() == null)
        advance(7_000)
        // Put-back reports the level as the deliberate check.
        assertEquals(
            listOf(BatteryReminder(19)),
            policy.onBatteryUpdate(battery(casePercent = 19)),
        )
        advance(6_000)
        // Lid close: the reading vanishes right before the session ends — the pending vanish
        // is retired by the session end, so no phantom take-out alarm ever fires and the
        // disconnect itself stays suppressed behind the put-back alarm.
        assertTrue(policy.onBatteryUpdate(battery(casePercent = null)).isEmpty())
        assertTrue(policy.onSessionEnded().isEmpty())
        assertTrue(policy.confirmVanish() == null)
    }

    @Test
    fun aConfirmedVanishWarnsWhenTheSessionSurvived() {
        val policy = policy()
        // A healthy session start stays silent.
        assertTrue(policy.onBatteryUpdate(battery(casePercent = 45)).isEmpty())
        advance(5_000)
        assertTrue(policy.onBatteryUpdate(battery(casePercent = null)).isEmpty())
        assertTrue(policy.hasPendingVanish())
        advance(1_000)
        // The session survived past the vanish, so the take-out is real — and the healthy
        // level keeps it silent here; the drop below the threshold is covered by the put-back.
        assertTrue(policy.confirmVanish() == null)
        advance(10_000)
        assertEquals(
            listOf(BatteryReminder(19)),
            policy.onBatteryUpdate(battery(casePercent = 19)),
        )
    }

    @Test
    fun putBackWithinTheGestureGapStaysSilent() {
        val policy = policy()
        assertEquals(
            listOf(BatteryReminder(19)),
            policy.onBatteryUpdate(battery(casePercent = 19)),
        )
        advance(2_000)
        assertTrue(policy.onBatteryUpdate(battery(casePercent = null)).isEmpty())
        advance(1_000)
        // The put-back right behind its take-out is one gesture, not a new alarm.
        assertTrue(policy.onBatteryUpdate(battery(casePercent = 19)).isEmpty())
    }

    @Test
    fun takeOutWithinTheGestureGapAfterAPutBackStaysSilent() {
        val policy = policy()
        assertTrue(policy.onBatteryUpdate(battery(casePercent = 45)).isEmpty())
        advance(5_000)
        assertTrue(policy.onBatteryUpdate(battery(casePercent = null)).isEmpty())
        advance(7_000)
        // The level dropped below the threshold while worn: the put-back warns on channel B.
        assertEquals(
            listOf(BatteryReminder(19)),
            policy.onBatteryUpdate(battery(casePercent = 19)),
        )
        advance(1_000)
        // One gesture covers both directions: the quick take-out stays silent even confirmed.
        assertTrue(policy.onBatteryUpdate(battery(casePercent = null)).isEmpty())
        advance(1_000)
        assertTrue(policy.confirmVanish() == null)
    }

    @Test
    fun persistingReadingsNeverWarnOnTheirOwn() {
        val policy = policy()
        assertEquals(
            listOf(BatteryReminder(19)),
            policy.onBatteryUpdate(battery(casePercent = 19)),
        )
        repeat(6) {
            advance(10_000)
            assertTrue(policy.onBatteryUpdate(battery(casePercent = 19)).isEmpty())
        }
    }

    @Test
    fun handlingOnlyModeIgnoresTheConnectionNodes() {
        val policy = policy(connectionAlarmsEnabled = false)
        assertTrue(policy.onBatteryUpdate(battery(casePercent = 19)).isEmpty())
        advance(31_000)
        assertTrue(policy.onSessionEnded().isEmpty())
        // Handling nodes keep working in the next session.
        advance(31_000)
        policy.onBatteryUpdate(battery(casePercent = 19))
        advance(2_000)
        assertTrue(policy.onBatteryUpdate(battery(casePercent = null)).isEmpty())
        advance(1_000)
        assertEquals(BatteryReminder(19), policy.confirmVanish())
    }

    @Test
    fun connectionOnlyModeIgnoresTheHandlingNodes() {
        val policy = policy(handlingAlarmsEnabled = false)
        assertEquals(
            listOf(BatteryReminder(19)),
            policy.onBatteryUpdate(battery(casePercent = 19)),
        )
        advance(2_000)
        assertTrue(policy.onBatteryUpdate(battery(casePercent = null)).isEmpty())
        advance(31_000)
        assertTrue(policy.onBatteryUpdate(battery(casePercent = 19)).isEmpty())
        advance(31_000)
        assertEquals(listOf(BatteryReminder(19)), policy.onSessionEnded())
    }

    @Test
    fun customThresholdShiftsTheWarningWindow() {
        val policy = policy(lowCasePercent = 50)
        assertEquals(
            listOf(BatteryReminder(45)),
            policy.onBatteryUpdate(battery(casePercent = 45)),
        )
        advance(2_000)
        assertTrue(policy.onBatteryUpdate(battery(casePercent = 51)).isEmpty())
        advance(2_000)
        assertTrue(policy.onBatteryUpdate(battery(casePercent = null)).isEmpty())
    }
}
