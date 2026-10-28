package dev.hyperears.integration

import dev.hyperears.protocol.sony.SonyHeadphonesWireCodec
import org.junit.Assert.*
import org.junit.Test

class SonyAdvancedControlsTest {
    @Test fun declaredEqAndValidGeometryStatusParametersPublishTypedState() {
        val adapter = requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(deviceName = "WH-1000XM4", standardHeadset = true)))
        adapter.beginHandshake()
        fun report(vararg p: Int) = adapter.receive(SonyHeadphonesWireCodec.encode(
            SonyHeadphonesWireCodec.MessageType.COMMAND_1, 0, p.map(Int::toByte).toByteArray()))
        report(1, 0, 0x40, 0x10)
        report(7, 0, 1, 0x51)
        report(0x53, 1, 0)
        report(0x5B, 1, 6, 0x10, 0, 0, 1, 1, 0x90, 1, 3, 0xE8, 1, 9, 0xC4, 1, 0x18, 0x9C, 1, 0x3E, 0x80)
        report(0x57, 1, 0, 6, 10, 10, 10, 10, 10, 10)
        assertTrue("Confirmed EQ must publish a typed feature", adapter.runtimeState().features.values.any { it.featureId == "sony.eq" })
    }
    @Test fun eqControlsRequireDeclaredTypeStatusAndParametersAndResetDropsEvidence() {
        val session = SonyDailyControlsSession(false)
        assertTrue(session.encode(SonyControlRequest.SetEqPreset(16)).isEmpty())
        assertNull(session.accept(b(0x57, 1, 0, 0)))
        session.accept(b(7, 0, 1, 0x51))
        session.accept(b(0x57, 1, 0, 0))
        assertTrue(session.encode(SonyControlRequest.SetEqPreset(16)).isEmpty())
        session.accept(b(0x53, 1, 0))
        val commands = session.encode(SonyControlRequest.SetEqPreset(16))
        assertEquals(listOf(listOf(0x58, 1, 16, 0), listOf(0x56, 1), listOf(0x52, 1)), commands.map { it.u() })
        assertEquals(0, session.eqState().preset)
        session.accept(b(0x55, 1, 1))
        assertTrue(session.encode(SonyControlRequest.SetEqPreset(16)).isEmpty())
        session.accept(b(7, 0, 0))
        assertNull(session.eqState().preset)
    }
    @Test fun geometryControlsTenBandScaleAndClearBassPreservesOtherBands() {
        val standard = SonyDailyControlsSession(false)
        standard.accept(b(7, 0, 1, 0x51)); standard.accept(b(0x53, 1, 0))
        standard.accept(b(0x5B, 1, 2, 0x10, 0, 0, 1, 1, 0x90))
        standard.accept(b(0x57, 1, 0, 2, 10, 13))
        assertEquals(listOf(0x58, 1, 255, 2, 6, 13), standard.encode(SonyControlRequest.SetClearBass(-4)).first().u())
        assertEquals(listOf(10, 13), standard.eqState().rawSteps)
        val ten = SonyDailyControlsSession(true)
        ten.accept(b(7, 0, 1, 0x55, 0)); ten.accept(b(0x53, 0x31, 0))
        ten.accept((listOf(0x5B, 0x31, 10) + (1..10).flatMap { listOf(1, 0, it * 20) }).map(Int::toByte).toByteArray())
        ten.accept((listOf(0x57, 0x31, 10) + List(10) { 6 }).map(Int::toByte).toByteArray())
        ten.accept((listOf(0x57, 0x31, 10) + List(10) { 13 }).map(Int::toByte).toByteArray())
        assertEquals(List(10) { 6 }, ten.eqState().rawSteps)
        assertEquals(6, ten.eqState().center)
        assertFalse(ten.eqState().writableBass)
        assertTrue(ten.encode(SonyControlRequest.SetClearBass(0)).isEmpty())
        assertTrue(ten.encode(SonyControlRequest.SetEqBands(List(10) { 7 }, requireNotNull(ten.eqState().definition))).isEmpty())
        assertEquals(listOf(0x58, 0x31, 10) + List(10) { 0 }, ten.encode(SonyControlRequest.SetEqBands(List(10) { -6 }, requireNotNull(ten.eqState().definition))).first().u())
    }
    @Test fun ultKeepsModeAndIndependentEbbNeedsItsOwnStatus() {
        val session = SonyDailyControlsSession(true)
        session.accept(b(7, 0, 2, 0x53, 0, 0x51, 1))
        session.accept(b(0x57, 3, 16, 2, 1, 10)); session.accept(b(0x53, 3, 0))
        assertEquals(listOf(0x58, 3, 20, 2, 0), session.encode(SonyControlRequest.SetEqPreset(20)).first().u())
        session.accept(b(0x57, 1, 0xFD))
        assertTrue(session.encode(SonyControlRequest.SetClearBass(2)).isEmpty())
        session.accept(b(0x53, 1, 0))
        assertEquals(listOf(0x58, 1, 2), session.encode(SonyControlRequest.SetClearBass(2)).first().u())
    }
    @Test fun ambientPrefersConfirmedDualLayoutAndPausesManualEditingDuringAdaptiveMode() {
        val session = SonyDailyControlsSession(true)
        session.accept(b(7, 0, 3, 0x68, 0, 0x6B, 1, 0x6D, 2))
        session.accept(b(0x67, 0x15, 1, 1, 1, 2, 0, 10))
        assertFalse(session.ambientState()!!.voiceWritable)
        session.accept(b(0x67, 0x17, 1, 1, 1, 1, 12))
        assertTrue(session.ambientState()!!.voiceWritable)
        assertEquals(listOf(0x68, 0x17, 1, 1, 1, 0, 8), session.encode(SonyControlRequest.SetAmbient(8, false)).first().u())
        assertEquals(listOf(0x68, 0x17, 1, 1, 0, 1, 12), session.noiseWrite(NoiseMode.ANC)!!.first().u())
        // New declaration removes the dual type; only the adaptive report may drive the UI now.
        session.accept(b(7, 0, 1, 0x6D, 0))
        session.accept(b(0x67, 0x19, 1, 1, 1, 0, 9, 1, 2))
        assertTrue(session.ambientState()!!.adaptive)
        assertTrue(session.encode(SonyControlRequest.SetAmbient(5, true)).isEmpty())
    }
    @Test fun tableTwoCannotUnlockTableOneCapabilitiesAndRequestsRoundTrip() {
        val adapter = requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(deviceName = "WH-1000XM4", standardHeadset = true)))
        adapter.beginHandshake()
        adapter.receive(SonyHeadphonesWireCodec.encode(SonyHeadphonesWireCodec.MessageType.COMMAND_1, 0, b(1, 0, 0x40, 0x10)))
        listOf(b(7, 0, 1, 0x51), b(0x53, 1, 0), b(0x57, 1, 0, 0)).forEach {
            adapter.receive(SonyHeadphonesWireCodec.encode(SonyHeadphonesWireCodec.MessageType.COMMAND_2, 0, it))
        }
        assertNull(adapter.runtimeState().features.get<SonyEqFeatureState>())
        listOf(SonyControlRequest.SetEqPreset(16), SonyControlRequest.SetEqBands(listOf(-1, 2), SonyEqDefinition(1, 1, listOf(SonyEqBand(16, 0), SonyEqBand(1, 400)), 10, null)),
            SonyControlRequest.SetClearBass(-4), SonyControlRequest.SetAmbient(12, true)).forEach {
            assertEquals(it, ControlRequestTransport.decode(ControlRequestTransport.encode(it)))
        }
        adapter.resetProtocolSession()
        assertNull(adapter.runtimeState().features.get<SonyEqFeatureState>())
    }
    @Test fun offReportReplacesRawModeWithoutInventingOrReenablingAmbientLevel() {
        val session = SonyDailyControlsSession(false)
        session.accept(b(7, 0, 1, 0x62))
        session.accept(b(0x67, 2, 1, 2, 0, 1, 1, 10))
        session.accept(b(0x67, 2, 0, 2, 0, 1, 1, 0))
        assertEquals(0, session.ambientState()!!.level)
        assertTrue(session.encode(SonyControlRequest.SetAmbient(8, false)).isEmpty())
        assertEquals(listOf(0x68, 2, 0x11, 2, 0, 1, 1, 10), session.noiseWrite(NoiseMode.TRANSPARENCY)!!.first().u())
    }
    @Test fun rejectedBusyAdvancedAndNoiseRequestsNeverExecuteAfterAckDrainAndIdleRetryWorks() {
        val adapter = requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(deviceName = "WH-1000XM4", standardHeadset = true)))
        adapter.beginHandshake()
        fun report(vararg p: Int) = adapter.receive(SonyHeadphonesWireCodec.encode(
            SonyHeadphonesWireCodec.MessageType.COMMAND_1, 0, b(*p)))
        report(1, 0, 0x40, 0x10)
        report(7, 0, 1, 0x51)
        report(0x53, 1, 0)
        report(0x57, 1, 0, 0)
        report(0x67, 2, 1, 2, 2, 1, 0, 0)
        val eq = SonyControlRequest.SetEqPreset(16)
        val noise = StandardControlRequest.SetNoiseMode(NoiseMode.OFF)
        assertFalse(adapter.executeControl(eq).accepted)
        assertFalse(adapter.executeControl(noise).accepted)
        var nextAck = 1
        fun drain(): List<SonyHeadphonesWireCodec.Frame> = buildList {
            repeat(20) {
                val result = adapter.receive(SonyHeadphonesWireCodec.encode(SonyHeadphonesWireCodec.MessageType.ACK, nextAck))
                nextAck = 1 - nextAck
                result.commands.forEach { addAll(SonyHeadphonesWireCodec.Decoder().offer(it)) }
            }
        }
        val drained = drain()
        assertTrue("A rejected request must never remain queued", drained.none {
            it.payload.firstOrNull()?.toInt()?.and(255) in setOf(0x58, 0x68)
        })
        val retriedEq = adapter.executeControl(eq)
        assertTrue(retriedEq.accepted)
        assertEquals(listOf(0x58, 1, 16, 0), SonyHeadphonesWireCodec.Decoder().offer(retriedEq.commands.single()).single().payload.u())
        drain()
        assertTrue(adapter.executeControl(noise).accepted)
    }
    @Test fun serializedBandRequestRejectsSameCountGeometryTypeAndUltChangesAtBothGates() {
        val session = SonyDailyControlsSession(true)
        session.accept(b(7, 0, 1, 0x53, 0)); session.accept(b(0x53, 3, 0))
        session.accept(b(0x5B, 3, 2, 0x10, 0, 0, 1, 1, 0x90))
        session.accept(b(0x57, 3, 16, 1, 2, 10, 10))
        val before = session.eqState()
        val request = SonyControlRequest.SetEqBands(listOf(1, 2), requireNotNull(before.definition))
        val decoded = ControlRequestTransport.decode(ControlRequestTransport.encode(request)) as SonyControlRequest.SetEqBands
        val adapter = requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(deviceName = "WH-1000XM4", standardHeadset = true)))
        adapter.beginHandshake()
        fun report(vararg p: Int) = adapter.receive(SonyHeadphonesWireCodec.encode(
            SonyHeadphonesWireCodec.MessageType.COMMAND_1, 0, b(*p)))
        report(1, 0, 3, 0, 0, 0, 0, 0)
        report(7, 0, 1, 0x53, 0); report(0x53, 3, 0)
        report(0x5B, 3, 2, 0x10, 0, 0, 1, 1, 0x90); report(0x57, 3, 16, 1, 2, 10, 10)
        assertTrue(adapter.supportsControl(decoded))
        report(0x5B, 3, 2, 0x10, 0, 0, 1, 3, 0x20)
        assertFalse(adapter.supportsControl(decoded))
        assertTrue(before.supports(decoded))
        assertTrue(session.encode(decoded).isNotEmpty())
        assertFalse(before.copy(parameterType = 0).supports(decoded))
        assertFalse(before.copy(ultMode = 2).supports(decoded))
        assertFalse(before.copy(bands = listOf(SonyEqBand(16, 0), SonyEqBand(1, 800))).supports(decoded))
        session.accept(b(0x5B, 3, 2, 0x10, 0, 0, 1, 3, 0x20))
        assertTrue(session.encode(decoded).isEmpty())
        session.accept(b(0x5B, 3, 2, 0x10, 0, 0, 1, 1, 0x90))
        session.accept(b(0x57, 3, 16, 2, 2, 10, 10))
        assertTrue(session.encode(decoded).isEmpty())
        session.accept(b(7, 0, 1, 0x50, 0)); session.accept(b(0x53, 0, 0))
        session.accept(b(0x5B, 0, 2, 0x10, 0, 0, 1, 1, 0x90))
        session.accept(b(0x57, 0, 16, 2, 10, 10))
        assertTrue(session.encode(decoded).isEmpty())
    }
    @Test fun firstZeroLevelReportPreservesRawFieldsForOffAncAndUsesExplicitSourceDefaultForAmbient() {
        val session = SonyDailyControlsSession(true)
        session.accept(b(7, 0, 1, 0x6D, 0))
        session.accept(b(0x67, 0x19, 1, 1, 0, 1, 0, 1, 2))
        assertEquals(listOf(0x68, 0x19, 1, 0, 0, 1, 0, 1, 2), session.noiseWrite(NoiseMode.OFF)!!.first().u())
        assertEquals(listOf(0x68, 0x19, 1, 1, 0, 1, 0, 1, 2), session.noiseWrite(NoiseMode.ANC)!!.first().u())
        assertEquals(listOf(0x68, 0x19, 1, 1, 1, 1, 10, 1, 2), session.noiseWrite(NoiseMode.TRANSPARENCY)!!.first().u())
        assertEquals(0, session.ambientState()!!.level)
        val v1 = SonyDailyControlsSession(false)
        v1.accept(b(7, 0, 1, 0x62)); v1.accept(b(0x67, 2, 0, 2, 0, 1, 1, 0))
        assertEquals(listOf(0x68, 2, 0x11, 2, 2, 1, 1, 0), v1.noiseWrite(NoiseMode.ANC)!!.first().u())
        assertEquals(listOf(0x68, 2, 0x11, 2, 0, 1, 1, 10), v1.noiseWrite(NoiseMode.TRANSPARENCY)!!.first().u())
    }
    @Test fun changedKnownGeometryWithdrawsPublishedParametersAndQueriesExactlyOnce() {
        val session = SonyDailyControlsSession(false)
        session.accept(b(7, 0, 1, 0x51)); session.accept(b(0x53, 1, 0))
        session.accept(b(0x57, 1, 16, 2, 10, 12))
        val first = session.accept(b(0x5B, 1, 2, 0x10, 0, 0, 1, 1, 0x90))!!
        assertTrue(first.queries.isEmpty())
        assertEquals(listOf(10, 12), session.eqState().rawSteps)
        assertTrue(session.eqState().writableBands)
        val changed = session.accept(b(0x5B, 1, 2, 0x10, 0, 0, 1, 3, 0x20))!!
        val published = changed.states.filterIsInstance<SonyEqFeatureState>().single()
        assertEquals(listOf(listOf(0x56, 1)), changed.queries.map { it.u() })
        assertTrue(published.rawSteps.isEmpty())
        assertNull(published.preset)
        assertFalse(published.writableBands)
        assertFalse(published.writablePreset)
        assertFalse(published.writableBass)
        assertTrue(session.accept(b(0x5B, 1, 2, 0x10, 0, 0, 1, 3, 0x20))!!.queries.isEmpty())
        session.accept(b(0x57, 1, 16, 2, 8, 11))
        assertEquals(listOf(8, 11), session.eqState().rawSteps)
        assertTrue(session.eqState().writableBands)
    }
    private fun b(vararg p: Int) = p.map(Int::toByte).toByteArray()
    private fun ByteArray.u() = map { it.toInt() and 255 }

}
