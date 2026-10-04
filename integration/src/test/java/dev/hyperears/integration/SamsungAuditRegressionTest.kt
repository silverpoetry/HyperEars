package dev.hyperears.integration

import dev.hyperears.protocol.samsung.SamsungBudsCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SamsungAuditRegressionTest {
    @Test
    fun expiredCycleTargetMustNotBeReplayedByLaterActionChange() {
        var now = 0L
        val session = SamsungBuds2ProProtocolSession(elapsedMs = { now })
        session.offer(SamsungBudsCodec.packet(0x61, statusPayload().apply { this[21] = 0x65 }))
        session.encode(SamsungControlRequest.SetTouchHoldNoiseCycles(
            SamsungNoiseCycle.AMBIENT_OFF, SamsungNoiseCycle.AMBIENT_OFF,
        ))
        now = 90_001L
        val commands = session.encode(SamsungControlRequest.SetTouchHoldActions(
            SamsungTouchAction.NOISE_CONTROL, SamsungTouchAction.VOICE_ASSISTANT,
        ))
        assertArrayEquals(SamsungBudsCodec.touchHoldNoiseCyclesCommand(6, 5), commands[1])
    }

    @Test
    fun unexpiredCycleTargetIsStillMergedIntoActionChange() {
        var now = 0L
        val session = SamsungBuds2ProProtocolSession(elapsedMs = { now })
        session.offer(SamsungBudsCodec.packet(0x61, statusPayload().apply { this[21] = 0x65 }))
        session.encode(SamsungControlRequest.SetTouchHoldNoiseCycles(
            SamsungNoiseCycle.AMBIENT_OFF, SamsungNoiseCycle.AMBIENT_OFF,
        ))
        now = 89_999L
        val commands = session.encode(SamsungControlRequest.SetTouchHoldActions(
            SamsungTouchAction.NOISE_CONTROL, SamsungTouchAction.VOICE_ASSISTANT,
        ))
        assertArrayEquals(SamsungBudsCodec.touchHoldNoiseCyclesCommand(3, 3), commands[1])
    }

    @Test
    fun extraHighAmbientRequiresAReportedFieldNotJustAModelName() {
        listOf(12 to 44, 12 to 46, 13 to 45).forEach { (revision, length) ->
            val adapter = SamsungBuds2ProAdapter()
            val payload = statusPayload().copyOf(length).apply { this[0] = revision.toByte() }
            adapter.receive(SamsungBudsCodec.packet(0x61, payload))
            assertFalse(adapter.supportsControl(SamsungControlRequest.SetExtraHighAmbient(true)))
            assertTrue(adapter.supportsControl(SamsungControlRequest.SetTouchpadLock(true)))
            val state = adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>()!!
            assertFalse(state.extraHighAmbientSupported)
            assertFalse(state.extraHighAmbientEnabled)
            // An unsolicited ACK must not promote a missing field into a capability.
            val ack = adapter.receive(SamsungBudsCodec.packet(0x42, byteArrayOf(0x96.toByte(), 1)))
            assertFalse(ack.stateChanged)
        }
    }

    @Test
    fun extraHighAmbientDisabledAndEnabledReportsBothProveSupport() {
        listOf(0, 1).forEach { value ->
            val adapter = SamsungBuds2ProAdapter()
            adapter.receive(SamsungBudsCodec.packet(0x61, statusPayload().apply { this[45] = value.toByte() }))
            assertTrue(adapter.supportsControl(SamsungControlRequest.SetExtraHighAmbient(true)))
            val state = adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>()!!
            assertTrue(state.extraHighAmbientSupported)
            assertEquals(value == 1, state.extraHighAmbientEnabled)
            adapter.resetProtocolSession()
            assertFalse(adapter.supportsControl(SamsungControlRequest.SetExtraHighAmbient(true)))
        }
    }

    @Test
    fun invalidExtendedSettingValuesMustNotBecomeConfirmedSettings() {
        val payload = statusPayload().apply {
            this[9] = 6
            this[11] = 0xFF.toByte()
            this[23] = 4
        }
        assertNull(SamsungBudsCodec.parseExtendedStatus(SamsungBudsCodec.Frame(0x61, payload)))
        val adapter = SamsungBuds2ProAdapter()
        adapter.receive(SamsungBudsCodec.packet(0x61, payload))
        assertNull(adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>())
        assertFalse(adapter.supportsControl(SamsungControlRequest.SetTouchpadLock(true)))
        assertEquals(100, adapter.runtimeState().battery.left.percent)
        assertTrue(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))
    }

    @Test
    fun invalidExtendedStatusDoesNotReplacePreviouslyConfirmedSettings() {
        val adapter = SamsungBuds2ProAdapter()
        adapter.receive(SamsungBudsCodec.packet(0x61, statusPayload()))
        val confirmed = adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>()
        adapter.receive(SamsungBudsCodec.packet(0x61, statusPayload().apply { this[11] = 0 }))
        assertEquals(confirmed, adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>())
    }

    @Test
    fun displayedCyclesFollowLatestConfirmedOrRequestedStateForEachEar() {
        val adapter = SamsungBuds2ProAdapter()
        adapter.receive(SamsungBudsCodec.packet(0x61, statusPayload().apply { this[21] = 0x65 }))
        adapter.executeControl(SamsungControlRequest.SetTouchHoldNoiseCycles(
            SamsungNoiseCycle.AMBIENT_OFF, SamsungNoiseCycle.ANC_AMBIENT,
        ))
        var state = adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>()!!
        assertEquals(SamsungNoiseCycle.AMBIENT_OFF, state.displayedLeftCycle)
        assertEquals(SamsungNoiseCycle.ANC_AMBIENT, state.displayedRightCycle)
        adapter.receive(SamsungBudsCodec.packet(0x42, byteArrayOf(0x79, 0, 1, 1, 1, 1, 0)))
        adapter.receive(SamsungBudsCodec.packet(0x61, statusPayload().apply { this[21] = 0x56 }))
        state = adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>()!!
        assertFalse(state.touchHoldCyclesPending)
        assertEquals(SamsungNoiseCycle.ANC_OFF, state.displayedLeftCycle)
        assertEquals(SamsungNoiseCycle.ANC_AMBIENT, state.displayedRightCycle)
    }

    @Test
    fun buds2ProHoldActionsRemoveOnlyTheInterCommandWaitWithoutReadbackOrFakeConfirmation() {
        val adapter = SamsungBuds2ProAdapter()
        adapter.receive(SamsungBudsCodec.packet(0x61, statusPayload()))
        val request = SamsungControlRequest.SetTouchHoldActions(
            SamsungTouchAction.VOICE_ASSISTANT, SamsungTouchAction.NOISE_CONTROL,
        )
        val policy = adapter.controlPolicy(request)
        assertEquals(0L, policy.commandGapMs)
        assertEquals(0L, policy.cooldownMs)
        val result = adapter.executeControl(request)
        assertEquals(listOf(0x92, 0x79), result.commands.map { SamsungBudsCodec.parseFrame(it)!!.id })
        assertTrue(result.readback.isEmpty())
        val state = adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>()!!
        assertTrue(state.touchHoldActionsPending)
        assertEquals(SamsungTouchAction.NOISE_CONTROL, state.touchHoldLeftAction)
        assertEquals(SamsungTouchAction.VOICE_ASSISTANT, state.displayedLeftAction)
        assertEquals(120L, adapter.controlPolicy(SamsungControlRequest.SetTouchpadLock(true)).commandGapMs)
        assertEquals(120L, adapter.controlPolicy(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)).commandGapMs)
    }

    @Test
    fun otherKnownSamsungModelsAlsoSubmitHoldActionsImmediately() {
        listOf(SamsungBudsCodec.Model.BUDS2, SamsungBudsCodec.Model.BUDS_FE).forEach { model ->
            val adapter = SamsungBuds2ProAdapter(model)
            adapter.receive(SamsungBudsCodec.packet(0x61, statusPayload()))
            assertEquals(0L, adapter.controlPolicy(SamsungControlRequest.SetTouchHoldActions(
                SamsungTouchAction.VOICE_ASSISTANT, SamsungTouchAction.NOISE_CONTROL,
            )).commandGapMs)
        }
    }

    private fun statusPayload(): ByteArray {
        val raw = ("FD 31 00 61 0D 04 64 64 01 00 33 58 00 01 BF 22 00 01 46 01 46 01 00 00 " +
            "03 35 00 03 00 10 01 01 01 01 32 03 01 01 01 00 0C AE 01 00 03 00 01 00 " +
            "01 01 B6 37 DD").split(" ").map { it.toInt(16).toByte() }.toByteArray()
        return SamsungBudsCodec.parseFrame(raw)!!.payload
    }
}
