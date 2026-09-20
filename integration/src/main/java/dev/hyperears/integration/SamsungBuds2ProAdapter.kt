package dev.hyperears.integration

import dev.hyperears.protocol.samsung.SamsungBudsCodec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** Samsung Galaxy Buds2 Pro (SM-R510) private RFCOMM integration. */
class SamsungBuds2ProAdapter(
    private val model: SamsungBudsCodec.Model = SamsungBudsCodec.Model.BUDS2_PRO,
) : StandardEarbudAdapter() {
    override val id: String = when (model) {
        SamsungBudsCodec.Model.BUDS2_PRO -> ID
        SamsungBudsCodec.Model.BUDS2 -> "samsung-galaxy-buds2"
        SamsungBudsCodec.Model.BUDS_FE -> "samsung-galaxy-buds-fe"
        SamsungBudsCodec.Model.UNKNOWN -> "samsung-galaxy-buds-family"
    }
    override val displayName: String = when (model) {
        SamsungBudsCodec.Model.BUDS2_PRO -> "Samsung Galaxy Buds2 Pro"
        SamsungBudsCodec.Model.BUDS2 -> "Samsung Galaxy Buds2"
        SamsungBudsCodec.Model.BUDS_FE -> "Samsung Galaxy Buds FE"
        SamsungBudsCodec.Model.UNKNOWN -> "Samsung Galaxy Buds (shared service)"
    }
    override val resolution: AdapterResolution = if (model == SamsungBudsCodec.Model.UNKNOWN)
        AdapterResolution.FAMILY_MATCH else AdapterResolution.EXACT_MATCH
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.ServiceUuid(BUDS2_PRO_UUID, "samsung-buds2-pro-rfcomm"),
    )
    override val controlApps: List<ControlAppSpec> = buildList {
        add(ControlAppCatalog.galaxyWearable)
        when (model) {
            SamsungBudsCodec.Model.BUDS2_PRO -> add(ControlAppCatalog.galaxyBuds2ProManager)
            SamsungBudsCodec.Model.BUDS2 -> add(ControlAppCatalog.galaxyBuds2Manager)
            SamsungBudsCodec.Model.BUDS_FE -> add(ControlAppCatalog.galaxyBudsFeManager)
            SamsungBudsCodec.Model.UNKNOWN -> Unit
        }
    }
    override val featureStateContract: DeviceFeatureStateContract =
        StandardDeviceFeatureStateContract.extending { _, state -> state is SamsungBudsSettingsFeatureState }
    override val controlRequestContract: ControlRequestContract =
        StandardControlRequestContract.extending { adapter, request ->
            val settings = adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>()
            settings != null && when (request) {
                is SamsungControlRequest.SetAmbientVolume -> request.level in 0..3
                is SamsungControlRequest.SetEqualizer -> request.preset in 0..4
                is SamsungControlRequest.SetTouchHoldNoiseCycles ->
                    settings.touchHoldLeftCycle != null && settings.touchHoldRightCycle != null
                is SamsungControlRequest.SetVoiceDetect -> settings.voiceDetectSupported
                is SamsungControlRequest.SetExtraHighAmbient -> model == SamsungBudsCodec.Model.BUDS2_PRO
                is SamsungControlRequest.SetTouchpadLock,
                is SamsungControlRequest.SetTouchGesture,
                is SamsungControlRequest.SetTouchHoldActions,
                is SamsungControlRequest.SetNoiseControlsWithOneEarbud,
                is SamsungControlRequest.SetSeamlessConnection,
                is SamsungControlRequest.SetOutsideDoubleTap,
                is SamsungControlRequest.SetSidetone,
                -> true
                else -> false
            }
        }

    override fun controlPolicy(request: ControlRequest): ControlExecutionPolicy {
        val current = runtimeState().features.get<SamsungBudsSettingsFeatureState>()
            ?: return super.controlPolicy(request)
        return when (request) {
            is SamsungControlRequest.SetTouchHoldActions -> ControlExecutionPolicy(
                confirmation = ControlConfirmationPolicy.PUBLISH_AFTER_WRITE,
                stateAfterWrite = current.copy(
                    requestedLeftAction = request.left,
                    requestedRightAction = request.right,
                    touchHoldActionsPending = true,
                    touchHoldActionsTimedOut = false,
                ),
            )
            is SamsungControlRequest.SetTouchHoldNoiseCycles -> ControlExecutionPolicy(
                confirmation = ControlConfirmationPolicy.PUBLISH_AFTER_WRITE,
                stateAfterWrite = current.copy(
                    requestedLeftCycle = request.left,
                    requestedRightCycle = request.right,
                    touchHoldCyclesPending = true,
                    touchHoldCyclesTimedOut = false,
                ),
            )
            else -> super.controlPolicy(request)
        }
    }
    override val miLinkCardPresentationId: MiLinkCardPresentationId?
        get() = SamsungMiLinkPresentationIds.BUDS2_PRO.takeIf {
            effectiveCapabilities().noiseControl &&
                runtimeState().features.get<SamsungBudsSettingsFeatureState>() != null
        }

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!identity.standardHeadset || identity.nativeSystemEarbud) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        val namedModel = when {
            MODEL_MARKERS.any(name::contains) -> SamsungBudsCodec.Model.BUDS2_PRO
            name.contains("buds2") || name.contains("smr177") -> SamsungBudsCodec.Model.BUDS2
            name.contains("budsfe") || name.contains("smr400") -> SamsungBudsCodec.Model.BUDS_FE
            else -> SamsungBudsCodec.Model.UNKNOWN
        }
        return if (model != SamsungBudsCodec.Model.UNKNOWN) namedModel == model else
            identity.serviceUuids.any { it.equals(BUDS2_PRO_UUID, ignoreCase = true) }
    }

    override fun createProtocolSession(): ProtocolSession = SamsungBuds2ProProtocolSession(model = model)

    override fun onProtocolReset() {
        removeFeatureState(SamsungBudsSettingsFeatureState.FEATURE_ID)
    }

    companion object {
        const val ID = "samsung-galaxy-buds2-pro"
        const val BUDS2_PRO_UUID = "2e73a4ad-332d-41fc-90e2-16bef06523f2"
        private val MODEL_MARKERS = setOf("galaxybuds2pro", "buds2pro", "smr510")
    }
}

object SamsungMiLinkPresentationIds {
    val BUDS2_PRO = MiLinkCardPresentationId("samsung-buds2-pro-settings")
}

@Serializable
@SerialName("samsung.buds_settings")
data class SamsungBudsSettingsFeatureState(
    val equalizerEnabled: Boolean,
    val equalizerPreset: Int,
    val touchpadLocked: Boolean,
    val singleTapEnabled: Boolean,
    val doubleTapEnabled: Boolean,
    val tripleTapEnabled: Boolean,
    val touchHoldEnabled: Boolean,
    val doubleTapCallEnabled: Boolean,
    val touchHoldCallEnabled: Boolean,
    val touchHoldLeftAction: SamsungTouchAction,
    val touchHoldRightAction: SamsungTouchAction,
    val ambientVolume: Int,
    val voiceDetectEnabled: Boolean,
    val noiseControlsWithOneEarbud: Boolean,
    val seamlessConnectionEnabled: Boolean,
    val outsideDoubleTapEnabled: Boolean,
    val sidetoneEnabled: Boolean,
    val extraHighAmbientEnabled: Boolean,
    val touchHoldActionsPending: Boolean = false,
    val touchHoldLeftCycle: SamsungNoiseCycle? = null,
    val touchHoldRightCycle: SamsungNoiseCycle? = null,
    val touchHoldCyclesPending: Boolean = false,
    val requestedLeftAction: SamsungTouchAction? = null,
    val requestedRightAction: SamsungTouchAction? = null,
    val requestedLeftCycle: SamsungNoiseCycle? = null,
    val requestedRightCycle: SamsungNoiseCycle? = null,
    val touchHoldActionsTimedOut: Boolean = false,
    val touchHoldCyclesTimedOut: Boolean = false,
    val voiceDetectSupported: Boolean = true,
) : DeviceFeatureState {
    @Transient
    override val featureId: String = FEATURE_ID

    val displayedLeftAction: SamsungTouchAction get() = requestedLeftAction ?: touchHoldLeftAction
    val displayedRightAction: SamsungTouchAction get() = requestedRightAction ?: touchHoldRightAction
    val displayedLeftCycle: SamsungNoiseCycle? get() = requestedLeftCycle ?: touchHoldLeftCycle
    val displayedRightCycle: SamsungNoiseCycle? get() = requestedRightCycle ?: touchHoldRightCycle

    // The wire command writes both ears, but an unchanged ear is already confirmed.
    val leftActionPending: Boolean get() = touchHoldActionsPending &&
        requestedLeftAction != null && requestedLeftAction != touchHoldLeftAction
    val rightActionPending: Boolean get() = touchHoldActionsPending &&
        requestedRightAction != null && requestedRightAction != touchHoldRightAction

    companion object { const val FEATURE_ID = "samsung.buds_settings" }
}

@Serializable
enum class SamsungTouchGesture {
    SINGLE_TAP,
    DOUBLE_TAP,
    TRIPLE_TAP,
    TOUCH_AND_HOLD,
    DOUBLE_TAP_CALL,
    TOUCH_AND_HOLD_CALL,
}

@Serializable
enum class SamsungTouchAction(val wire: Int) {
    VOICE_ASSISTANT(1),
    NOISE_CONTROL(2),
    VOLUME(3),
    SPOTIFY(4),
}

@Serializable
enum class SamsungNoiseCycle(val mask: Int) {
    ANC_AMBIENT(6), ANC_OFF(5), AMBIENT_OFF(3),
}

@Serializable
sealed interface SamsungControlRequest : ControlRequest {
    @Serializable @SerialName("samsung.set_ambient_volume")
    data class SetAmbientVolume(val level: Int) : SamsungControlRequest
    @Serializable @SerialName("samsung.set_equalizer")
    data class SetEqualizer(val enabled: Boolean, val preset: Int) : SamsungControlRequest
    @Serializable @SerialName("samsung.set_touchpad_lock")
    data class SetTouchpadLock(val enabled: Boolean) : SamsungControlRequest
    @Serializable @SerialName("samsung.set_touch_gesture")
    data class SetTouchGesture(
        val gesture: SamsungTouchGesture,
        val enabled: Boolean,
    ) : SamsungControlRequest
    @Serializable @SerialName("samsung.set_touch_hold_actions")
    data class SetTouchHoldActions(
        val left: SamsungTouchAction,
        val right: SamsungTouchAction,
    ) : SamsungControlRequest
    @Serializable @SerialName("samsung.set_touch_hold_noise_cycles")
    data class SetTouchHoldNoiseCycles(
        val left: SamsungNoiseCycle,
        val right: SamsungNoiseCycle,
    ) : SamsungControlRequest
    @Serializable @SerialName("samsung.set_voice_detect")
    data class SetVoiceDetect(val enabled: Boolean) : SamsungControlRequest
    @Serializable @SerialName("samsung.set_one_earbud_noise_controls")
    data class SetNoiseControlsWithOneEarbud(val enabled: Boolean) : SamsungControlRequest
    @Serializable @SerialName("samsung.set_seamless_connection")
    data class SetSeamlessConnection(val enabled: Boolean) : SamsungControlRequest
    @Serializable @SerialName("samsung.set_outside_double_tap")
    data class SetOutsideDoubleTap(val enabled: Boolean) : SamsungControlRequest
    @Serializable @SerialName("samsung.set_sidetone")
    data class SetSidetone(val enabled: Boolean) : SamsungControlRequest
    @Serializable @SerialName("samsung.set_extra_high_ambient")
    data class SetExtraHighAmbient(val enabled: Boolean) : SamsungControlRequest
}

internal class SamsungBuds2ProProtocolSession(
    private val model: SamsungBudsCodec.Model = SamsungBudsCodec.Model.BUDS2_PRO,
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000L },
) : ProtocolSession {
    private val decoder = SamsungBudsCodec.Decoder()
    private var handshakePublished = false
    private var managerInfoSent = false
    private var settings: SamsungBudsCodec.SettingsState? = null
    private var pendingTouchHoldActions: Pair<Int, Int>? = null
    private var pendingTouchHoldCycles: Pair<Int, Int>? = null
    private var actionsDeadline = 0L
    private var cyclesDeadline = 0L
    private var actionsTimedOut = false
    private var cyclesTimedOut = false
    private val pendingGestures = mutableMapOf<SamsungTouchGesture, Boolean>()
    private var pendingTouchLock: Boolean? = null
    private var touchDeadline = 0L
    private var outsideDoubleTapBeforeTouchLock: Boolean? = null

    override fun initialReadCommands(): List<ByteArray> = emptyList()

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        StandardControlRequest.Refresh -> listOf(SamsungBudsCodec.managerInfoCommand())
        is StandardControlRequest.SetNoiseMode -> request.mode.toSamsungMode()
            ?.let(SamsungBudsCodec::noiseModeCommand)?.let(::listOf).orEmpty()
        is SamsungControlRequest.SetAmbientVolume ->
            listOf(SamsungBudsCodec.packet(SamsungBudsCodec.AMBIENT_VOLUME, byteArrayOf(request.level.toByte())))
        is SamsungControlRequest.SetEqualizer ->
            listOf(SamsungBudsCodec.equalizerCommand(request.enabled, request.preset))
        is SamsungControlRequest.SetTouchpadLock -> settings?.let { confirmed ->
            expirePending()
            val current = touchTarget(confirmed)
            pendingTouchLock = request.enabled
            touchDeadline = elapsedMs() + HOLD_CONFIRMATION_WINDOW_MS
            buildList {
                add(SamsungBudsCodec.touchpadLockCommand(request.enabled, current))
                if (request.enabled) {
                    outsideDoubleTapBeforeTouchLock = current.outsideDoubleTapEnabled
                    if (current.outsideDoubleTapEnabled) {
                        add(SamsungBudsCodec.boolCommand(SamsungBudsCodec.OUTSIDE_DOUBLE_TAP, false))
                    }
                } else {
                    val restoreOutsideDoubleTap = outsideDoubleTapBeforeTouchLock
                        ?: current.outsideDoubleTapEnabled
                    if (current.outsideDoubleTapEnabled != restoreOutsideDoubleTap) {
                        add(
                            SamsungBudsCodec.boolCommand(
                                SamsungBudsCodec.OUTSIDE_DOUBLE_TAP,
                                restoreOutsideDoubleTap,
                            ),
                        )
                    }
                    outsideDoubleTapBeforeTouchLock = null
                }
            }
        }.orEmpty()
        is SamsungControlRequest.SetTouchGesture -> settings?.let { confirmed ->
            expirePending()
            pendingGestures[request.gesture] = request.enabled
            touchDeadline = elapsedMs() + HOLD_CONFIRMATION_WINDOW_MS
            val target = touchTarget(confirmed)
            listOf(SamsungBudsCodec.touchpadLockCommand(target.touchpadLocked, target))
        }.orEmpty()
        is SamsungControlRequest.SetTouchHoldActions -> {
            pendingTouchHoldActions = request.left.wire to request.right.wire
            actionsDeadline = elapsedMs() + HOLD_CONFIRMATION_WINDOW_MS
            actionsTimedOut = false
            buildList {
                add(SamsungBudsCodec.touchpadOptionsCommand(request.left.wire, request.right.wire))
                // Match the reference client's action-change sequence. 0x88 is manager
                // metadata, not a readback or a save command.
                val current = settings
                val leftCycle = pendingTouchHoldCycles?.first ?: current?.touchHoldLeftCycle
                val rightCycle = pendingTouchHoldCycles?.second ?: current?.touchHoldRightCycle
                if ((request.left == SamsungTouchAction.NOISE_CONTROL ||
                        request.right == SamsungTouchAction.NOISE_CONTROL) &&
                    leftCycle != null && rightCycle != null
                ) {
                    add(SamsungBudsCodec.touchHoldNoiseCyclesCommand(
                        leftCycle, rightCycle,
                    ))
                }
            }
        }
        is SamsungControlRequest.SetTouchHoldNoiseCycles -> {
            pendingTouchHoldCycles = request.left.mask to request.right.mask
            cyclesDeadline = elapsedMs() + HOLD_CONFIRMATION_WINDOW_MS
            cyclesTimedOut = false
            listOf(SamsungBudsCodec.touchHoldNoiseCyclesCommand(request.left.mask, request.right.mask))
        }
        is SamsungControlRequest.SetVoiceDetect ->
            listOf(SamsungBudsCodec.boolCommand(SamsungBudsCodec.SET_DETECT_CONVERSATIONS, request.enabled))
        is SamsungControlRequest.SetNoiseControlsWithOneEarbud ->
            listOf(SamsungBudsCodec.boolCommand(SamsungBudsCodec.SET_ANC_WITH_ONE_EARBUD, request.enabled))
        is SamsungControlRequest.SetSeamlessConnection ->
            listOf(SamsungBudsCodec.seamlessConnectionCommand(request.enabled))
        is SamsungControlRequest.SetOutsideDoubleTap ->
            listOf(SamsungBudsCodec.boolCommand(SamsungBudsCodec.OUTSIDE_DOUBLE_TAP, request.enabled))
        is SamsungControlRequest.SetSidetone ->
            listOf(SamsungBudsCodec.boolCommand(SamsungBudsCodec.SET_SIDETONE, request.enabled))
        is SamsungControlRequest.SetExtraHighAmbient ->
            listOf(SamsungBudsCodec.boolCommand(SamsungBudsCodec.EXTRA_HIGH_AMBIENT, request.enabled))
        else -> emptyList()
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        decoder.offer(bytes).forEach { raw ->
            val frame = SamsungBudsCodec.parseFrame(raw) ?: return@forEach
            // Expiry never converts a desired value into a confirmed device report.
            val expired = expirePending()
            var settingsPublished = false
            var accepted = false
            SamsungBudsCodec.parseBattery(frame, model)?.let { battery ->
                accepted = true
                add(ProtocolEvent.CapabilitiesIdentified(battery = true))
                add(ProtocolEvent.FeatureStateChanged(BatteryFeatureState(battery.toDomain())))
            }
            SamsungBudsCodec.parseNoiseMode(frame)?.let { mode ->
                accepted = true
                add(ProtocolEvent.CapabilitiesIdentified(false, THREE_STATE_NOISE_MODES))
                add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(mode.toDomain())))
            }
            SamsungBudsCodec.parseExtendedStatus(frame, model)?.settings?.let { reported ->
                accepted = true
                confirmPending(reported)
                settings = reported
                settingsPublished = true
                add(ProtocolEvent.FeatureStateChanged(reported.toDomain()))
            }
            SamsungBudsCodec.parseAcknowledgement(frame)?.let { ack ->
                when (ack.commandId) {
                    SamsungBudsCodec.NOISE_CONTROLS -> ack.parameters.firstOrNull()
                        ?.toInt()?.and(0xFF)?.toSamsungNoiseMode()?.let { mode ->
                            accepted = true
                            add(ProtocolEvent.CapabilitiesIdentified(false, THREE_STATE_NOISE_MODES))
                            add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(mode.toDomain())))
                        }
                    else -> settings?.applyAcknowledgement(ack)?.let { reported ->
                        accepted = true
                        confirmPending(reported,
                            confirmActions = ack.commandId == SamsungBudsCodec.SET_TOUCHPAD_OPTION,
                            confirmCycles = ack.commandId == SamsungBudsCodec.SET_TOUCH_AND_HOLD_NOISE_CONTROLS,
                            confirmTouch = ack.commandId == SamsungBudsCodec.LOCK_TOUCHPAD,
                            clearTimedOut = true,
                        )
                        settings = reported
                        settingsPublished = true
                        add(ProtocolEvent.FeatureStateChanged(reported.toDomain()))
                    }
                }
            }
            if (expired && !settingsPublished) settings?.let {
                add(ProtocolEvent.FeatureStateChanged(it.toDomain()))
            }
            if (accepted && !handshakePublished) {
                handshakePublished = true
                add(ProtocolEvent.HandshakeAccepted)
            }
        }
    }

    override fun followUpCommands(event: ProtocolEvent): List<ByteArray> {
        if (event !is ProtocolEvent.HandshakeAccepted || managerInfoSent) return emptyList()
        managerInfoSent = true
        return listOf(SamsungBudsCodec.managerInfoCommand())
    }

    override fun reset() {
        decoder.reset()
        handshakePublished = false
        managerInfoSent = false
        settings = null
        outsideDoubleTapBeforeTouchLock = null
        pendingTouchHoldActions = null
        pendingTouchHoldCycles = null
        pendingGestures.clear()
        pendingTouchLock = null
        actionsTimedOut = false
        cyclesTimedOut = false
    }

    private fun confirmPending(
        reported: SamsungBudsCodec.SettingsState,
        confirmActions: Boolean = true,
        confirmCycles: Boolean = true,
        confirmTouch: Boolean = true,
        clearTimedOut: Boolean = false,
    ) {
        if (confirmActions && pendingTouchHoldActions ==
                (reported.touchHoldLeftAction to reported.touchHoldRightAction)) {
            pendingTouchHoldActions = null
            actionsTimedOut = false
        }
        if (confirmCycles && pendingTouchHoldCycles ==
                (reported.touchHoldLeftCycle to reported.touchHoldRightCycle)) {
            pendingTouchHoldCycles = null
            cyclesTimedOut = false
        }
        if (clearTimedOut && confirmActions && pendingTouchHoldActions == null) actionsTimedOut = false
        if (clearTimedOut && confirmCycles && pendingTouchHoldCycles == null) cyclesTimedOut = false
        if (confirmTouch) {
            if (pendingTouchLock == reported.touchpadLocked) pendingTouchLock = null
            pendingGestures.entries.removeAll { (gesture, enabled) -> reported.gestureValue(gesture) == enabled }
        }
    }

    private fun expirePending(): Boolean {
        val now = elapsedMs()
        var changed = false
        if (pendingTouchHoldActions != null && now >= actionsDeadline) {
            pendingTouchHoldActions = null
            actionsTimedOut = true
            changed = true
        }
        if (pendingTouchHoldCycles != null && now >= cyclesDeadline) {
            pendingTouchHoldCycles = null
            cyclesTimedOut = true
            changed = true
        }
        if (now >= touchDeadline) {
            pendingGestures.clear()
            pendingTouchLock = null
        }
        return changed
    }

    private fun SamsungBudsCodec.SettingsState.gestureValue(gesture: SamsungTouchGesture): Boolean = when (gesture) {
        SamsungTouchGesture.SINGLE_TAP -> singleTapEnabled
        SamsungTouchGesture.DOUBLE_TAP -> doubleTapEnabled
        SamsungTouchGesture.TRIPLE_TAP -> tripleTapEnabled
        SamsungTouchGesture.TOUCH_AND_HOLD -> touchHoldEnabled
        SamsungTouchGesture.DOUBLE_TAP_CALL -> doubleTapCallEnabled
        SamsungTouchGesture.TOUCH_AND_HOLD_CALL -> touchHoldCallEnabled
    }

    private fun touchTarget(confirmed: SamsungBudsCodec.SettingsState): SamsungBudsCodec.SettingsState =
        confirmed.copy(
            touchpadLocked = pendingTouchLock ?: confirmed.touchpadLocked,
            singleTapEnabled = pendingGestures[SamsungTouchGesture.SINGLE_TAP] ?: confirmed.singleTapEnabled,
            doubleTapEnabled = pendingGestures[SamsungTouchGesture.DOUBLE_TAP] ?: confirmed.doubleTapEnabled,
            tripleTapEnabled = pendingGestures[SamsungTouchGesture.TRIPLE_TAP] ?: confirmed.tripleTapEnabled,
            touchHoldEnabled = pendingGestures[SamsungTouchGesture.TOUCH_AND_HOLD] ?: confirmed.touchHoldEnabled,
            doubleTapCallEnabled = pendingGestures[SamsungTouchGesture.DOUBLE_TAP_CALL] ?: confirmed.doubleTapCallEnabled,
            touchHoldCallEnabled = pendingGestures[SamsungTouchGesture.TOUCH_AND_HOLD_CALL] ?: confirmed.touchHoldCallEnabled,
        )

    private fun SamsungBudsCodec.BatteryState.toDomain() = EarbudBattery(
        left = BatteryReading(left, leftCharging), right = BatteryReading(right, rightCharging),
        case = BatteryReading(case, caseCharging),
    )
    private fun SamsungBudsCodec.NoiseMode.toDomain() = when (this) {
        SamsungBudsCodec.NoiseMode.OFF -> NoiseMode.OFF
        SamsungBudsCodec.NoiseMode.ANC -> NoiseMode.ANC
        SamsungBudsCodec.NoiseMode.AMBIENT -> NoiseMode.TRANSPARENCY
    }
    private fun Int.toSamsungNoiseMode(): SamsungBudsCodec.NoiseMode? =
        SamsungBudsCodec.NoiseMode.entries.firstOrNull { it.wire == this }

    private fun SamsungBudsCodec.SettingsState.applyAcknowledgement(
        ack: SamsungBudsCodec.Acknowledgement,
    ): SamsungBudsCodec.SettingsState? {
        val value = ack.parameters.firstOrNull()?.toInt()?.and(0xFF) ?: return null
        return when (ack.commandId) {
            SamsungBudsCodec.SET_DETECT_CONVERSATIONS -> copy(voiceDetectEnabled = value != 0)
            SamsungBudsCodec.LOCK_TOUCHPAD -> copy(
                // Buds2 Pro echoes the protocol's touch-enabled bit here. The command
                // encodes a locked touchpad as 0 and an unlocked touchpad as 1.
                touchpadLocked = value == 0,
                singleTapEnabled = ack.parameters.getOrNull(1)?.let { it.toInt() == 1 }
                    ?: singleTapEnabled,
                doubleTapEnabled = ack.parameters.getOrNull(2)?.let { it.toInt() == 1 }
                    ?: doubleTapEnabled,
                tripleTapEnabled = ack.parameters.getOrNull(3)?.let { it.toInt() == 1 }
                    ?: tripleTapEnabled,
                touchHoldEnabled = ack.parameters.getOrNull(4)?.let { it.toInt() == 1 }
                    ?: touchHoldEnabled,
                doubleTapCallEnabled = ack.parameters.getOrNull(5)?.let { it.toInt() == 1 }
                    ?: doubleTapCallEnabled,
                touchHoldCallEnabled = ack.parameters.getOrNull(6)?.let { it.toInt() == 1 }
                    ?: touchHoldCallEnabled,
            )
            SamsungBudsCodec.SET_TOUCHPAD_OPTION -> {
                val right = ack.parameters.getOrNull(1)?.toInt()?.and(0xFF) ?: return null
                if (value !in 1..4 || right !in 1..4) return null
                copy(touchHoldLeftAction = value, touchHoldRightAction = right)
            }
            SamsungBudsCodec.SET_TOUCH_AND_HOLD_NOISE_CONTROLS -> {
                val p = ack.parameters
                if (p.size != 6 || p.any { it.toInt() !in 0..1 }) return null
                fun mask(offset: Int) = SamsungBudsCodec.parseNoiseCycle(
                    (p[offset].toInt() shl 2) or (p[offset + 1].toInt() shl 1) or p[offset + 2].toInt(),
                )
                copy(touchHoldLeftCycle = mask(0) ?: return null,
                    touchHoldRightCycle = mask(3) ?: return null)
            }
            SamsungBudsCodec.AMBIENT_VOLUME -> copy(ambientVolume = value.coerceIn(0, 3))
            SamsungBudsCodec.EQUALIZER -> copy(
                equalizerEnabled = value != 0,
                equalizerPreset = (value - 1).coerceIn(0, 4),
            )
            SamsungBudsCodec.SET_ANC_WITH_ONE_EARBUD ->
                copy(noiseControlsWithOneEarbud = value != 0)
            SamsungBudsCodec.SET_SEAMLESS_CONNECTION ->
                copy(seamlessConnectionEnabled = value == 0)
            SamsungBudsCodec.OUTSIDE_DOUBLE_TAP -> copy(outsideDoubleTapEnabled = value != 0)
            SamsungBudsCodec.SET_SIDETONE -> copy(sidetoneEnabled = value != 0)
            SamsungBudsCodec.EXTRA_HIGH_AMBIENT -> copy(extraHighAmbientEnabled = value != 0)
            else -> null
        }
    }
    private fun NoiseMode.toSamsungMode() = when (this) {
        NoiseMode.OFF -> SamsungBudsCodec.NoiseMode.OFF
        NoiseMode.ANC -> SamsungBudsCodec.NoiseMode.ANC
        NoiseMode.TRANSPARENCY -> SamsungBudsCodec.NoiseMode.AMBIENT
        NoiseMode.WIND -> null
    }
    private fun SamsungBudsCodec.SettingsState.toDomain() = SamsungBudsSettingsFeatureState(
        equalizerEnabled = equalizerEnabled,
        equalizerPreset = equalizerPreset,
        touchpadLocked = touchpadLocked,
        singleTapEnabled = singleTapEnabled,
        doubleTapEnabled = doubleTapEnabled,
        tripleTapEnabled = tripleTapEnabled,
        touchHoldEnabled = touchHoldEnabled,
        doubleTapCallEnabled = doubleTapCallEnabled,
        touchHoldCallEnabled = touchHoldCallEnabled,
        touchHoldLeftAction = SamsungTouchAction.entries.firstOrNull { it.wire == touchHoldLeftAction }
            ?: SamsungTouchAction.NOISE_CONTROL,
        touchHoldRightAction = SamsungTouchAction.entries.firstOrNull { it.wire == touchHoldRightAction }
            ?: SamsungTouchAction.NOISE_CONTROL,
        ambientVolume = ambientVolume,
        voiceDetectEnabled = voiceDetectEnabled,
        noiseControlsWithOneEarbud = noiseControlsWithOneEarbud,
        seamlessConnectionEnabled = seamlessConnectionEnabled,
        outsideDoubleTapEnabled = outsideDoubleTapEnabled,
        sidetoneEnabled = sidetoneEnabled,
        extraHighAmbientEnabled = extraHighAmbientEnabled,
        touchHoldActionsPending = pendingTouchHoldActions != null,
        touchHoldLeftCycle = SamsungNoiseCycle.entries.firstOrNull { it.mask == touchHoldLeftCycle },
        touchHoldRightCycle = SamsungNoiseCycle.entries.firstOrNull { it.mask == touchHoldRightCycle },
        touchHoldCyclesPending = pendingTouchHoldCycles != null,
        requestedLeftAction = SamsungTouchAction.entries.firstOrNull { it.wire == pendingTouchHoldActions?.first },
        requestedRightAction = SamsungTouchAction.entries.firstOrNull { it.wire == pendingTouchHoldActions?.second },
        requestedLeftCycle = SamsungNoiseCycle.entries.firstOrNull { it.mask == pendingTouchHoldCycles?.first },
        requestedRightCycle = SamsungNoiseCycle.entries.firstOrNull { it.mask == pendingTouchHoldCycles?.second },
        touchHoldActionsTimedOut = actionsTimedOut,
        touchHoldCyclesTimedOut = cyclesTimedOut,
        voiceDetectSupported = model == SamsungBudsCodec.Model.BUDS2_PRO,
    )

    private companion object {
        const val HOLD_CONFIRMATION_WINDOW_MS = 90_000L
        val THREE_STATE_NOISE_MODES = setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY)
    }
}
