package dev.hyperears.integration

import dev.hyperears.protocol.sony.SonyMultipointCodec as Wire
import dev.hyperears.protocol.sony.SonyHeadphonesWireCodec as Transport
import org.junit.Assert.*
import org.junit.Test

class SonyMultipointTest {
    private fun b(vararg p: Int) = p.map(Int::toByte).toByteArray()
    private fun cap(slot: Int, title: String = "MULTIPOINT_SETTING") =
        b(0xD1, slot, 0, 1, title.length) + title.toByteArray() + b(0)
    private fun ready(session: SonyMultipointSession, slot: Int = 0xD2) {
        session.accept(b(7, 0, 1, slot, 0)); session.accept(cap(slot))
        session.accept(b(0xD3, slot, 0)); session.accept(b(0xD7, slot, 0, 1))
    }
    @Test fun semanticSlotNeedsCompleteCapabilityAvailabilityAndParameterEvidence() {
        val session = SonyMultipointSession(true)
        val request = SonyControlRequest.SetMultipoint(SonyMultipointDefinition(0xD2), true)
        assertTrue(session.encode(request).isEmpty())
        session.accept(b(7, 0, 1, 0xD2, 0))
        assertEquals(listOf(0xD0, 0xD2, 1), session.reads().single().u())
        session.accept(b(0xD7, 0xD2, 0, 1)); assertNull(session.state.enabled)
        session.accept(cap(0xD2)); session.accept(b(0xD7, 0xD2, 0, 1))
        assertTrue(session.encode(request).isEmpty())
        session.accept(b(0xD3, 0xD2, 0))
        val commands = session.encode(request)
        assertEquals(listOf(0x94, 0, 0), commands[0].u())
        assertEquals(listOf(0xD8, 0xD2, 0, 0), commands[1].u())
        assertEquals(listOf(0xD6, 0xD2), commands[2].u())
        assertEquals(false, session.state.enabled)
    }
    @Test fun pendingToggleCannotBeReplacedByInverseAfterEarlyTargetReport() {
        val session = SonyMultipointSession(true)
        ready(session)
        val definition = session.state.definition!!
        val enable = SonyControlRequest.SetMultipoint(definition, true)
        assertFalse(session.encode(enable).isEmpty())
        // Before any report the session gate must already serialize the transaction.
        assertTrue(session.encode(enable.copy(enabled = false)).isEmpty())
        session.accept(b(0xD9, 0xD2, 0, 0))
        assertEquals(true, session.state.enabled)
        assertNotNull(session.state.pendingTransaction)
        assertFalse(session.state.supports(enable.copy(enabled = false)))
        assertTrue(session.encode(enable.copy(enabled = false)).isEmpty())
        session.accept(b(0x99, 0, 7, 0))
        assertEquals(true, session.state.alert!!.target)
        assertEquals(session.state.pendingTransaction, session.state.alert!!.transaction)
    }
    @Test fun unsolicitedExpiredAndUnrelatedAlertsNeverPermitReply() {
        var now = 0L
        val session = SonyMultipointSession(true) { now }
        ready(session)
        assertNull(session.accept(b(0x99, 0, 7, 0)))
        session.encode(SonyControlRequest.SetMultipoint(session.state.definition!!, true))
        assertNull(session.accept(b(0x99, 0, 113, 0)))
        now = 30_000
        assertNull(session.accept(b(0x99, 0, 7, 0)))
        assertNull(session.state.alert)
    }
    @Test fun displayedAlertExpiresAndCancellationNeverMutatesConfirmedValue() {
        var now = 0L
        val session = SonyMultipointSession(true) { now }
        ready(session)
        val definition = session.state.definition!!
        session.encode(SonyControlRequest.SetMultipoint(definition, true))
        session.accept(b(0x99, 0, 7, 0))
        val stale = SonyControlRequest.ReplyMultipointAlert(definition, session.state.alert!!, false)
        now = 30_000
        assertTrue(session.encode(stale).isEmpty())
        session.encode(SonyControlRequest.SetMultipoint(definition, true))
        session.accept(b(0x99, 0, 7, 0))
        assertTrue(session.encode(stale).isEmpty())
        val cancel = stale.copy(expectedAlert = session.state.alert!!)
        assertEquals(listOf(0x98, 0, 7, 0), session.encode(cancel).first().u())
        assertEquals(false, session.state.enabled)
        assertTrue(session.encode(cancel).isEmpty())
    }
    @Test fun explicitReplyMatchesTransactionAndMessageAndDoesNotConfirmValue() {
        val session = SonyMultipointSession(true)
        ready(session)
        val definition = session.state.definition!!
        session.encode(SonyControlRequest.SetMultipoint(definition, true))
        session.accept(b(0xD9, 0xD2, 0, 0)) // Early target report may precede the device alert.
        session.accept(b(0xD9, 0xD2, 0, 1)) // Every report remains authoritative.
        session.accept(b(0x99, 0, 7, 0))
        val alert = session.state.alert!!
        val request = SonyControlRequest.ReplyMultipointAlert(definition, alert, true)
        assertTrue(session.encode(request.copy(expectedAlert = alert.copy(transaction = alert.transaction + 1))).isEmpty())
        session.accept(b(0x99, 0, 6, 0))
        assertTrue(session.encode(request).isEmpty())
        val latest = request.copy(expectedAlert = session.state.alert!!)
        assertEquals(listOf(0x98, 0, 6, 1), session.encode(latest).first().u())
        assertEquals(false, session.state.enabled)
        assertTrue(session.encode(latest).isEmpty())
        assertNull(session.accept(b(0x99, 0, 6, 0)))
        session.accept(b(0xD9, 0xD2, 0, 0)); assertEquals(true, session.state.enabled)
    }
    @Test fun duplicateSemanticSlotsAndChangedDefinitionsRevokeOldDrafts() {
        val session = SonyMultipointSession(true)
        ready(session)
        val old = SonyControlRequest.SetMultipoint(session.state.definition!!, true)
        session.accept(b(7, 0, 2, 0xD2, 0, 0xD3, 1))
        assertNull(session.state.definition)
        session.accept(cap(0xD3)); assertNull(session.state.definition)
        session.accept(cap(0xD2, "OTHER_SETTING"))
        assertEquals(0xD3, session.state.definition!!.slot)
        assertNull(session.state.enabled)
        session.accept(b(0xD7, 0xD3, 0, 1)); session.accept(b(0xD3, 0xD3, 0))
        assertTrue(session.encode(old).isEmpty())
        assertFalse(session.encode(old.copy(expected = session.state.definition!!)).isEmpty())
        session.accept(b(7, 0, 0)); assertFalse(session.state.hasSettings)
    }
    @Test fun nonBooleanAdvertisedSlotCompletesDiscoveryWithoutBecomingWritable() {
        val session = SonyMultipointSession(true)
        session.accept(b(7, 0, 2, 0xD1, 0, 0xD2, 1))
        session.accept(cap(0xD2))
        assertNull(session.state.definition)
        val other = cap(0xD1, "OTHER_LIST_SETTING").apply { this[2] = 1 }
        session.accept(other)
        assertEquals(SonyMultipointDefinition(0xD2), session.state.definition)
        session.accept(b(0xD3, 0xD2, 0)); session.accept(b(0xD7, 0xD2, 0, 1))
        assertTrue(session.state.supports(SonyControlRequest.SetMultipoint(SonyMultipointDefinition(0xD2), true)))
        assertFalse(session.state.supports(SonyControlRequest.SetMultipoint(SonyMultipointDefinition(0xD1), true)))
    }
    @Test fun v1NeverUsesV2GsAndRequestsRoundTrip() {
        val v1 = SonyMultipointSession(false)
        assertNull(v1.accept(b(7, 0, 1, 0xD2)))
        assertNull(v1.accept(cap(0xD2)))
        val definition = SonyMultipointDefinition(0xD2)
        listOf(SonyControlRequest.SetMultipoint(definition, true),
            SonyControlRequest.ReplyMultipointAlert(definition, SonyMultipointAlert(2, 7, true), false)).forEach {
            assertEquals(it, ControlRequestTransport.decode(ControlRequestTransport.encode(it)))
        }
    }
    @Test fun adapterKeepsTableIsolationBusyAdmissionAndConfirmationReadback() {
        val adapter = requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(deviceName = "WH-1000XM4", standardHeadset = true)))
        adapter.beginHandshake()
        fun report(payload: ByteArray, type: Transport.MessageType = Transport.MessageType.COMMAND_1) = adapter.receive(Transport.encode(type, 0, payload))
        report(b(1, 0, 3, 0, 0, 0, 0, 0))
        report(b(7, 0, 1, 0xD2, 0), Transport.MessageType.COMMAND_2)
        report(cap(0xD2), Transport.MessageType.COMMAND_2)
        assertNull(adapter.runtimeState().features.get<SonyMultipointFeatureState>())
        report(b(7, 0, 1, 0xD2, 0)); report(cap(0xD2))
        report(b(0xD3, 0xD2, 0)); report(b(0xD7, 0xD2, 0, 1))
        val request = SonyControlRequest.SetMultipoint(SonyMultipointDefinition(0xD2), true)
        assertTrue(adapter.supportsControl(request))
        assertFalse(adapter.executeControl(request).accepted)
        var ack = 1
        fun drain() = buildList {
            repeat(30) {
                val result = adapter.receive(Transport.encode(Transport.MessageType.ACK, ack)); ack = 1 - ack
                result.commands.forEach { addAll(Transport.Decoder().offer(it)) }
            }
        }
        assertTrue(drain().none { it.payload.firstOrNull()?.toInt()?.and(255) in setOf(0x94, 0xD8) })
        report(b(0x99, 0, 7, 0))
        assertNull(adapter.runtimeState().features.get<SonyMultipointFeatureState>()!!.alert)
        val written = adapter.executeControl(request)
        assertTrue(written.accepted)
        assertEquals(listOf(0x94, 0, 0), Transport.Decoder().offer(written.commands.single()).single().payload.u())
        assertTrue(drain().any { it.payload.u() == listOf(0xD8, 0xD2, 0, 0) })
        report(b(0x99, 0, 7, 0))
        val state = adapter.runtimeState().features.get<SonyMultipointFeatureState>()!!
        val reply = SonyControlRequest.ReplyMultipointAlert(state.definition!!, state.alert!!, false)
        assertEquals(listOf(0x98, 0, 7, 0), Transport.Decoder().offer(adapter.executeControl(reply).commands.single()).single().payload.u())
        assertEquals(false, adapter.runtimeState().features.get<SonyMultipointFeatureState>()!!.enabled)
        adapter.resetProtocolSession()
        assertNull(adapter.runtimeState().features.get<SonyMultipointFeatureState>())
        assertFalse(adapter.supportsControl(reply))
    }
    private fun ByteArray.u() = map { it.toInt() and 255 }
}
