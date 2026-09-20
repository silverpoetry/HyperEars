package dev.hyperears.integration

import dev.hyperears.protocol.samsung.SamsungBudsCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SamsungBuds2ProAdapterTest {
    @Test
    fun exactNameAndServiceUuidResolveBeforeStandardFallback() {
        val byName = EarbudAdapterRegistry.resolve(EarbudIdentity("Galaxy Buds2 Pro (1234)", true))
        assertEquals(SamsungBuds2ProAdapter.ID, byName?.id)
        val byCustomizedName = EarbudAdapterRegistry.resolve(
            EarbudIdentity("尚振雨 的 Buds2 Pro", true),
        )
        assertEquals(SamsungBuds2ProAdapter.ID, byCustomizedName?.id)
        val byService = EarbudAdapterRegistry.resolve(
            EarbudIdentity("SM-R510", true, serviceUuids = setOf(SamsungBuds2ProAdapter.BUDS2_PRO_UUID)),
        )
        assertEquals(SamsungBuds2ProAdapter.ID, byService?.id)
    }

    @Test
    fun privateControlsUnlockOnlyAfterValidExtendedStatus() {
        val adapter = SamsungBuds2ProAdapter()
        assertFalse(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))
        assertEquals(null, adapter.miLinkCardPresentationId)

        val result = adapter.receive(extendedStatus())
        assertEquals(HandshakeResult.Ready, result.handshake)
        assertTrue(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))
        assertTrue(adapter.supportsControl(SamsungControlRequest.SetVoiceDetect(false)))
        assertEquals(SamsungMiLinkPresentationIds.BUDS2_PRO, adapter.miLinkCardPresentationId)
        assertNotNull(adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>())
        assertEquals(100, adapter.runtimeState().battery.left.percent)
    }

    @Test
    fun noiseAndSettingsCommandsUseSamsungMessageIds() {
        val adapter = SamsungBuds2ProAdapter()
        adapter.receive(extendedStatus())
        val noise = adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.TRANSPARENCY))
        assertTrue(noise.accepted)
        assertFalse(noise.stateChanged)
        assertEquals(NoiseMode.OFF, adapter.runtimeState().noiseMode)
        val noiseAck = adapter.receive(
            SamsungBudsCodec.packet(
                SamsungBudsCodec.UNIVERSAL_ACK,
                byteArrayOf(SamsungBudsCodec.NOISE_CONTROLS.toByte(), 2),
            ),
        )
        assertTrue(noiseAck.stateChanged)
        assertEquals(NoiseMode.TRANSPARENCY, adapter.runtimeState().noiseMode)
        assertEquals(SamsungBudsCodec.NOISE_CONTROLS, SamsungBudsCodec.parseFrame(noise.commands.single())?.id)
        val voice = adapter.executeControl(SamsungControlRequest.SetVoiceDetect(false))
        assertTrue(voice.accepted)
        assertFalse(voice.stateChanged)
        adapter.receive(
            SamsungBudsCodec.packet(
                SamsungBudsCodec.UNIVERSAL_ACK,
                byteArrayOf(SamsungBudsCodec.SET_DETECT_CONVERSATIONS.toByte(), 0),
            ),
        )
        assertFalse(
            requireNotNull(adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>())
                .voiceDetectEnabled,
        )
        assertEquals(
            SamsungBudsCodec.SET_DETECT_CONVERSATIONS,
            SamsungBudsCodec.parseFrame(voice.commands.single())?.id,
        )
    }

    @Test
    fun touchLockUsesAdvancedBuds2ProPayloadAndWaitsForAck() {
        val adapter = SamsungBuds2ProAdapter()
        adapter.receive(extendedStatus())

        val control = adapter.executeControl(SamsungControlRequest.SetTouchpadLock(true))
        val touchFrame = requireNotNull(SamsungBudsCodec.parseFrame(control.commands[0]))
        val outsideDoubleTapFrame = requireNotNull(SamsungBudsCodec.parseFrame(control.commands[1]))

        assertTrue(control.accepted)
        assertFalse(control.stateChanged)
        assertEquals(2, control.commands.size)
        assertEquals(SamsungBudsCodec.LOCK_TOUCHPAD, touchFrame.id)
        assertArrayEquals(byteArrayOf(0, 1, 1, 1, 1, 1, 1), touchFrame.payload)
        assertEquals(SamsungBudsCodec.OUTSIDE_DOUBLE_TAP, outsideDoubleTapFrame.id)
        assertArrayEquals(byteArrayOf(0), outsideDoubleTapFrame.payload)
        assertFalse(
            requireNotNull(adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>())
                .touchpadLocked,
        )

        val acknowledgement = adapter.receive(
            SamsungBudsCodec.packet(
                SamsungBudsCodec.UNIVERSAL_ACK,
                byteArrayOf(SamsungBudsCodec.LOCK_TOUCHPAD.toByte(), 0, 1, 1, 1, 1, 1, 1),
            ),
        )
        assertTrue(acknowledgement.stateChanged)
        assertTrue(
            requireNotNull(adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>())
                .touchpadLocked,
        )

        adapter.receive(
            SamsungBudsCodec.packet(
                SamsungBudsCodec.UNIVERSAL_ACK,
                byteArrayOf(SamsungBudsCodec.OUTSIDE_DOUBLE_TAP.toByte(), 0),
            ),
        )
        assertFalse(
            requireNotNull(adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>())
                .outsideDoubleTapEnabled,
        )

        val unlock = adapter.executeControl(SamsungControlRequest.SetTouchpadLock(false))
        assertEquals(2, unlock.commands.size)
        assertArrayEquals(
            byteArrayOf(1, 1, 1, 1, 1, 1, 1),
            requireNotNull(SamsungBudsCodec.parseFrame(unlock.commands[0])).payload,
        )
        assertEquals(
            SamsungBudsCodec.OUTSIDE_DOUBLE_TAP,
            requireNotNull(SamsungBudsCodec.parseFrame(unlock.commands[1])).id,
        )
        assertArrayEquals(
            byteArrayOf(1),
            requireNotNull(SamsungBudsCodec.parseFrame(unlock.commands[1])).payload,
        )
    }

    @Test
    fun individualGesturesAndHoldActionsUseSamsungPayloads() {
        val adapter = SamsungBuds2ProAdapter()
        adapter.receive(extendedStatus())

        val gesture = adapter.executeControl(
            SamsungControlRequest.SetTouchGesture(SamsungTouchGesture.DOUBLE_TAP, false),
        )
        val gestureFrame = requireNotNull(SamsungBudsCodec.parseFrame(gesture.commands.single()))
        assertEquals(SamsungBudsCodec.LOCK_TOUCHPAD, gestureFrame.id)
        assertArrayEquals(byteArrayOf(1, 1, 0, 1, 1, 1, 1), gestureFrame.payload)

        val actions = adapter.executeControl(
            SamsungControlRequest.SetTouchHoldActions(
                SamsungTouchAction.NOISE_CONTROL,
                SamsungTouchAction.VOLUME,
            ),
        )
        val actionFrame = requireNotNull(SamsungBudsCodec.parseFrame(actions.commands.first()))
        assertEquals(2, actions.commands.size)
        assertEquals(SamsungBudsCodec.SET_TOUCH_AND_HOLD_NOISE_CONTROLS,
            SamsungBudsCodec.parseFrame(actions.commands[1])?.id)
        assertEquals(SamsungBudsCodec.SET_TOUCHPAD_OPTION, actionFrame.id)
        assertArrayEquals(byteArrayOf(2, 3), actionFrame.payload)
        assertTrue(actions.stateChanged)
        assertTrue(
            requireNotNull(adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>())
                .touchHoldActionsPending,
        )

        adapter.receive(extendedStatus())
        val whileWaiting = requireNotNull(
            adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>(),
        )
        assertEquals(SamsungTouchAction.NOISE_CONTROL, whileWaiting.touchHoldRightAction)
        assertEquals(SamsungTouchAction.VOLUME, whileWaiting.displayedRightAction)
        assertTrue(whileWaiting.touchHoldActionsPending)

        adapter.receive(
            SamsungBudsCodec.packet(
                SamsungBudsCodec.UNIVERSAL_ACK,
                byteArrayOf(SamsungBudsCodec.SET_TOUCHPAD_OPTION.toByte(), 2, 3),
            ),
        )
        val settings = requireNotNull(adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>())
        assertEquals(SamsungTouchAction.NOISE_CONTROL, settings.touchHoldLeftAction)
        assertEquals(SamsungTouchAction.VOLUME, settings.touchHoldRightAction)
        assertFalse(settings.touchHoldActionsPending)
    }

    @Test
    fun noiseCyclesWriteBothSidesAndOnlyMatchingReportsConfirm() {
        val adapter = SamsungBuds2ProAdapter()
        adapter.receive(extendedStatus())
        val request = SamsungControlRequest.SetTouchHoldNoiseCycles(
            SamsungNoiseCycle.ANC_AMBIENT, SamsungNoiseCycle.AMBIENT_OFF,
        )
        assertEquals(request, ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
        val control = adapter.executeControl(request)
        assertTrue(control.accepted)
        assertArrayEquals(byteArrayOf(1, 1, 0, 0, 1, 1),
            SamsungBudsCodec.parseFrame(control.commands.single())?.payload)
        adapter.receive(extendedStatus())
        assertTrue(adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>()!!.touchHoldCyclesPending)
        adapter.receive(SamsungBudsCodec.packet(SamsungBudsCodec.UNIVERSAL_ACK,
            byteArrayOf(0x79, 1, 1, 0, 0, 1, 1)))
        val state = adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>()!!
        assertFalse(state.touchHoldCyclesPending)
        assertEquals(SamsungNoiseCycle.ANC_AMBIENT, state.touchHoldLeftCycle)
        assertEquals(SamsungNoiseCycle.AMBIENT_OFF, state.touchHoldRightCycle)
    }

    @Test
    fun reconnectUsesDeviceReadbackWithoutSendingCachedOrDefaultActions() {
        val adapter = SamsungBuds2ProAdapter()
        adapter.receive(extendedStatus())
        adapter.executeControl(SamsungControlRequest.SetTouchHoldActions(
            SamsungTouchAction.VOICE_ASSISTANT, SamsungTouchAction.NOISE_CONTROL))
        adapter.resetProtocolSession()
        assertEquals(null, adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>())
        val payload = SamsungBudsCodec.parseFrame(extendedStatus())!!.payload.copyOf()
        payload[11] = 0x12
        payload[21] = 0x65
        adapter.receive(SamsungBudsCodec.packet(SamsungBudsCodec.EXTENDED_STATUS_UPDATED, payload))
        val state = adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>()!!
        assertEquals(SamsungTouchAction.VOICE_ASSISTANT, state.touchHoldLeftAction)
        assertEquals(SamsungTouchAction.NOISE_CONTROL, state.touchHoldRightAction)
        assertEquals(SamsungNoiseCycle.ANC_AMBIENT, state.touchHoldLeftCycle)
        assertEquals(SamsungNoiseCycle.ANC_OFF, state.touchHoldRightCycle)
        assertFalse(state.touchHoldActionsPending)
        assertFalse(state.touchHoldCyclesPending)
    }

    @Test
    fun staleAndTruncatedAckCannotOverwriteNewActionAndPendingIsBounded() {
        var now = 0L
        val session = SamsungBuds2ProProtocolSession(elapsedMs = { now })
        session.offer(extendedStatus())
        session.encode(SamsungControlRequest.SetTouchHoldActions(
            SamsungTouchAction.VOICE_ASSISTANT, SamsungTouchAction.VOLUME))
        session.encode(SamsungControlRequest.SetTouchHoldActions(
            SamsungTouchAction.VOLUME, SamsungTouchAction.VOICE_ASSISTANT))
        assertTrue(session.offer(SamsungBudsCodec.packet(0x42, byteArrayOf(0x92.toByte(), 1))).isEmpty())
        val stale = session.offer(SamsungBudsCodec.packet(0x42, byteArrayOf(0x92.toByte(), 1, 3)))
            .filterIsInstance<ProtocolEvent.FeatureStateChanged>().single().state as SamsungBudsSettingsFeatureState
        assertEquals(SamsungTouchAction.VOICE_ASSISTANT, stale.touchHoldLeftAction)
        assertEquals(SamsungTouchAction.VOLUME, stale.displayedLeftAction)
        assertTrue(stale.touchHoldActionsPending)
        now = 90_001L
        val reported = session.offer(extendedStatus()).filterIsInstance<ProtocolEvent.FeatureStateChanged>()
            .map { it.state }.filterIsInstance<SamsungBudsSettingsFeatureState>().single()
        assertEquals(SamsungTouchAction.NOISE_CONTROL, reported.touchHoldLeftAction)
        assertFalse(reported.touchHoldActionsPending)
        assertTrue(reported.touchHoldActionsTimedOut)
        assertEquals(null, reported.requestedLeftAction)
    }

    @Test
    fun rapidGestureChangesMergeIntoTheNextCompleteTouchPayload() {
        val adapter = SamsungBuds2ProAdapter()
        adapter.receive(extendedStatus())

        val single = adapter.executeControl(
            SamsungControlRequest.SetTouchGesture(SamsungTouchGesture.SINGLE_TAP, false),
        )
        val double = adapter.executeControl(
            SamsungControlRequest.SetTouchGesture(SamsungTouchGesture.DOUBLE_TAP, false),
        )

        assertArrayEquals(byteArrayOf(1, 0, 1, 1, 1, 1, 1),
            SamsungBudsCodec.parseFrame(single.commands.single())?.payload)
        assertArrayEquals(byteArrayOf(1, 0, 0, 1, 1, 1, 1),
            SamsungBudsCodec.parseFrame(double.commands.single())?.payload)
    }

    @Test
    fun knownSamsungModelsUseExactAdaptersAndRenamedSharedServiceUsesFamilyFallback() {
        val buds2 = EarbudAdapterRegistry.resolve(EarbudIdentity("Galaxy Buds2", true))!!
        assertEquals("samsung-galaxy-buds2", buds2.id)
        assertEquals(ControlAppCatalog.galaxyBuds2Manager, buds2.controlApps.last())
        val budsFe = EarbudAdapterRegistry.resolve(EarbudIdentity("Galaxy Buds FE", true))!!
        assertEquals("samsung-galaxy-buds-fe", budsFe.id)
        assertEquals(ControlAppCatalog.galaxyBudsFeManager, budsFe.controlApps.last())
        val renamed = EarbudAdapterRegistry.resolve(EarbudIdentity(
            "我的耳机", true, serviceUuids = setOf(SamsungBuds2ProAdapter.BUDS2_PRO_UUID),
        ))
        assertEquals("samsung-galaxy-buds-family", renamed?.id)
        assertEquals(listOf(ControlAppCatalog.galaxyWearable), renamed?.controlApps)
        renamed!!.receive(extendedStatus())
        assertTrue(renamed.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))
        assertEquals(null, renamed.runtimeState().features.get<SamsungBudsSettingsFeatureState>())
    }

    @Test
    fun changingSecondEarDoesNotMarkConfirmedFirstEarPending() {
        val adapter = SamsungBuds2ProAdapter()
        adapter.receive(extendedStatus())
        adapter.executeControl(SamsungControlRequest.SetTouchHoldActions(
            SamsungTouchAction.VOICE_ASSISTANT, SamsungTouchAction.NOISE_CONTROL))
        var state = adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>()!!
        assertTrue(state.leftActionPending)
        assertFalse(state.rightActionPending)
        adapter.receive(SamsungBudsCodec.packet(0x42, byteArrayOf(0x92.toByte(), 1, 2)))
        adapter.executeControl(SamsungControlRequest.SetTouchHoldActions(
            SamsungTouchAction.VOICE_ASSISTANT, SamsungTouchAction.VOICE_ASSISTANT))
        state = adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>()!!
        assertFalse(state.leftActionPending)
        assertTrue(state.rightActionPending)
        adapter.receive(SamsungBudsCodec.packet(0x42, byteArrayOf(0x92.toByte(), 1, 1)))
        state = adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>()!!
        assertFalse(state.leftActionPending)
        assertFalse(state.rightActionPending)
    }

    private fun extendedStatus(): ByteArray = hex(
        "FD 31 00 61 0D 04 64 64 01 00 33 58 00 01 BF 22 00 01 46 01 46 01 00 00 " +
            "03 35 00 03 00 10 01 01 01 01 32 03 01 01 01 00 0C AE 01 00 03 00 01 00 " +
            "01 01 B6 37 DD",
    )

    private fun hex(value: String): ByteArray = value.trim().split(Regex("\\s+"))
        .map { it.toInt(16).toByte() }.toByteArray()
}
