package dev.hyperears.presentation

import dev.hyperears.integration.*

/** Connection-scoped ownership; each acquisition and temporary release require fresh 0x39 acks. */
internal class AppleLongPressLease {
    enum class Write { ACQUIRE, RELEASE }
    private enum class Phase { IDLE, WAITING, ACTIVE, WAIT_RELEASE, STOPPED }
    private var phase = Phase.IDLE
    private var baselineReport = -1L
    private var expectedMask = -1
    private var originalMask = -1
    private var lastStem = -1L
    var deadline: Long? = null
        private set

    fun update(eligible: Boolean, mask: Int?, report: Long, stem: Long?, now: Long): Write? {
        // Events received before admission/ack are consumed, never replayed after takeover.
        if (phase != Phase.ACTIVE && stem != null) lastStem = maxOf(lastStem, stem)
        if (phase == Phase.WAIT_RELEASE) {
            if (now >= deadline!!) { phase = Phase.STOPPED; deadline = null; return null }
            if (report <= baselineReport) return null
            if (mask != originalMask) { phase = Phase.STOPPED; deadline = null; return null }
            phase = Phase.IDLE
            deadline = null
        }
        if ((phase == Phase.ACTIVE || (phase == Phase.WAITING && report > baselineReport)) && mask != expectedMask) return stop()
        if (!eligible) {
            if (phase == Phase.WAITING || phase == Phase.ACTIVE) {
                phase = Phase.WAIT_RELEASE
                baselineReport = report
                deadline = now + ACK_TIMEOUT_MS
                return Write.RELEASE
            }
            return null
        }
        when (phase) {
            Phase.IDLE -> {
                if (mask == null) return null
                if (mask and 8 != 0) { phase = Phase.STOPPED; return null }
                baselineReport = report
                originalMask = mask
                expectedMask = mask or 8
                phase = Phase.WAITING
                deadline = now + ACK_TIMEOUT_MS
                return Write.ACQUIRE
            }
            Phase.WAITING -> {
                if (now >= deadline!!) return stop()
                if (report > baselineReport) {
                    if (mask != expectedMask) return stop()
                    phase = Phase.ACTIVE
                    deadline = null
                }
            }
            Phase.ACTIVE -> if (mask != expectedMask) return stop()
            Phase.WAIT_RELEASE -> Unit
            Phase.STOPPED -> Unit
        }
        return null
    }

    fun consume(stem: AppleStemFeatureState?): Boolean {
        if (stem == null || stem.sequence <= lastStem) return false
        lastStem = stem.sequence
        return phase == Phase.ACTIVE && stem.press == AppleStemPress.LONG
    }

    /** Release also covers an unacknowledged write that might already be applied remotely. */
    fun stop(): Write? {
        val owned = phase == Phase.WAITING || phase == Phase.ACTIVE
        phase = Phase.STOPPED
        deadline = null
        return if (owned) Write.RELEASE else null
    }

    companion object { const val ACK_TIMEOUT_MS = 3_000L }
}

internal object AppleLongPressActions {
    fun cycle(settings: AppleGestureSettingsFeatureState, listening: AppleListeningModeFeatureState?): List<AppleListeningMode> {
        val mask = settings.listeningCycleMask ?: return emptyList()
        if (mask !in 1..15 || listening == null) return emptyList()
        val modes = AppleListeningMode.entries.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
        return modes.takeIf { it.size >= 2 && listening.supportedModes.containsAll(it) && listening.mode in it }
            ?: emptyList()
    }

    fun eligible(settings: AppleGestureSettingsFeatureState?, listening: AppleListeningModeFeatureState?): Boolean {
        settings ?: return false
        val actions = listOf(settings.leftLongPress, settings.rightLongPress)
        return actions.all { it != null } && AppleLongPressAction.DIGITAL_ASSISTANT in actions &&
            (AppleLongPressAction.NOISE_CONTROL !in actions || cycle(settings, listening).isNotEmpty())
    }
}
