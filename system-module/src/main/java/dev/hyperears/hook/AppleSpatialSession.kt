package dev.hyperears.hook

import dev.hyperears.integration.*
import dev.hyperears.protocol.apple.*

/** One producer/session only; caller serializes events with its existing session mutex. */
internal class AppleSpatialSession(
    private val host: AppleSpatialHost,
    private val write: (ByteArray) -> Boolean,
    private val publish: (AppleSpatialFeatureState) -> Unit,
    private val clock: () -> Long,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
) : AutoCloseable {
    private var generation = 0L
    private var sequence = 256
    private var service: Int? = null
    private var subscribed = false
    private var acknowledged = false
    private var requested = false
    private var tracking = false
    private var startedAt = 0L
    private var eligible = false
    private var cancelDeadline: (() -> Unit)? = null
    private var latest: Quaternion? = null
    private var latestAt = 0L
    private var neutral: Quaternion? = null
    private var calibration = MotionCalibration()
    private var matrix: List<Double>? = null
    private var calibrating = false
    private var calibrationUntil = 0L
    private var collecting = false
    private var ownership: ByteArray? = null
    private var priorOwnership: ByteArray? = null
    private var ownershipWritten = false
    private var state = AppleSpatialFeatureState(false, false)

    fun updateEligibility(value: Boolean) {
        eligible = value
        if (!value || !host.rendererAvailable) stop()
        emit()
    }
    private fun emit() {
        state = state.copy(rendererAvailable = eligible && host.rendererAvailable,
            trackerAvailable = eligible && host.trackerAvailable, pending = requested && state.mode != AppleSpatialMode.TRACKING && !calibrating,
            calibrated = matrix != null, calibrationStep = if (calibrating) calibration.progress else -1,
            collecting = collecting)
        publish(state)
    }
    fun request(request: AppleSpatialControlRequest): Boolean {
        if (request is AppleSpatialControlRequest.SetMode && request.mode == AppleSpatialMode.OFF) {
            stop(); return true
        }
        if (!eligible || !host.rendererAvailable) return false
        when (request) {
            is AppleSpatialControlRequest.SetMode -> when (request.mode) {
                AppleSpatialMode.OFF -> stop()
                AppleSpatialMode.FIXED -> { stop(); if (!host.fixed()) return false; state = state.copy(mode = AppleSpatialMode.FIXED); emit() }
                AppleSpatialMode.TRACKING -> { if (matrix == null || !host.trackerAvailable) return false; begin(false) }
            }
            AppleSpatialControlRequest.BeginCalibration -> { if (!host.trackerAvailable) return false; begin(true) }
            AppleSpatialControlRequest.CalibrationStep -> {
                if (!calibrating || !acknowledged || clock() - latestAt !in 0..250) return false
                if (!collecting) { calibration.begin(latest ?: return false); collecting = true }
                else {
                    collecting = false
                    if (!calibration.finish()) { emit(); return false }
                    if (calibration.progress == 3) { matrix = calibration.matrix(); stop() }
                }
                emit()
            }
            AppleSpatialControlRequest.Recenter -> {
                if (!tracking || clock() - latestAt !in 0..250) return false
                neutral = latest
            }
        }
        return true
    }
    private fun begin(forCalibration: Boolean) {
        stop(); requested = true; calibrating = forCalibration
        if (forCalibration) { calibration = MotionCalibration(); calibrationUntil = clock() + 30000 }
        if (!AppleMotionCodec.rtBuddySetup().all(write) || !write(AppleMotionCodec.descriptorRequest(++sequence))) { stop(); return }
        deadline(2500); emit()
    }
    private fun deadline(delay: Long) {
        cancelDeadline?.invoke()
        val token = generation
        cancelDeadline = schedule(delay) { if (generation == token) stop() }
    }
    fun onPacket(packet: ByteArray) {
        if (packet.size == 11 && packet.take(7) == listOf<Byte>(4,0,4,0,9,0,6) && packet[7].toInt() in 0..1) {
            ownership = packet.copyOfRange(7,11)
            if (requested && ownershipWritten && ownership!![0].toInt() == 1 && service != null && !subscribed) subscribe()
        }
        if (!requested || !eligible) return
        if (service == null) {
            val candidates = AppleMotionCodec.services(packet).filter { it.name == "devmotion6" }
            if (candidates.size == 1) {
                service = candidates.single().id
                val prior = ownership
                // Unknown ownership never permits a write that cannot be restored.
                if (prior == null) { stop(); return }
                if (prior[0].toInt() == 1) subscribe()
                else {
                    priorOwnership = prior.copyOf(); ownershipWritten = true
                    if (!write(byteArrayOf(4,0,4,0,9,0,6,1) + prior.copyOfRange(1,4))) stop()
                }
            }
        }
        AppleMotionCodec.acknowledgement(packet)?.let {
            if (subscribed && it.service == service) {
                if (it.error != 0L) { stop(); return }
                acknowledged = true
            }
        }
        if (!acknowledged) return
        val pose = AppleMotionCodec.decode(packet, service) ?: return
        latest = pose.quaternion; latestAt = clock()
        if (calibrating) {
            if (collecting) calibration.sample(pose.quaternion)
            // Samples keep the short stale guard, never extend the total calibration lease.
            val remaining = calibrationUntil - clock()
            if (remaining <= 0) { stop(); return }
            deadline(minOf(800L, remaining))
            return
        }
        if (!tracking) {
            if (!host.start()) { stop(); return }
            neutral = pose.quaternion; tracking = true; startedAt = clock()
        }
        val vector = MotionCalibration.apply(matrix ?: run { stop(); return },
            (neutral!!.inverse() * pose.quaternion).rotationVector())
        if (!host.pose(vector)) { stop(); return }
        if (!host.trackingActive && clock() - startedAt >= 2500) { stop(); return }
        deadline(if (host.trackingActive) 800 else (2500 - (clock() - startedAt)).coerceAtLeast(1))
        if (host.trackingActive && state.mode != AppleSpatialMode.TRACKING) { state = state.copy(mode = AppleSpatialMode.TRACKING); emit() }
    }
    private fun subscribe() {
        val id = service ?: return
        subscribed = true
        if (!write(AppleMotionCodec.subscription(id, 20000, ++sequence))) { stop(); return }
        deadline(2500)
        emit()
    }
    private fun stop() {
        generation++; cancelDeadline?.invoke(); cancelDeadline = null
        val id = service; service = null
        if (subscribed && id != null) runCatching { write(AppleMotionCodec.subscription(id, 0, ++sequence)) }
        if (ownershipWritten && ownership?.get(0)?.toInt() == 1) priorOwnership?.let { before ->
            runCatching { write(byteArrayOf(4,0,4,0,9,0,6,before[0]) + checkNotNull(ownership).copyOfRange(1,4)) }
        }
        priorOwnership = null; ownershipWritten = false; subscribed = false; acknowledged = false
        requested = false; tracking = false; calibrating = false; collecting = false
        latest = null; neutral = null; host.close()
        state = state.copy(mode = AppleSpatialMode.OFF); emit()
    }
    override fun close() { eligible = false; stop(); host.dispose(); ownership = null; matrix = null }
}
