package dev.hyperears.integration

import dev.hyperears.protocol.oppo.OppoWireCodec
import org.junit.Assert.*
import org.junit.Test

class OppoLongSettingsTest {
    private val name = "N".repeat(130)
    private val definition = OppoEqualizerDefinition(9, name, -6, 6, listOf(1000))
    private val request = OppoControlRequest.UpdateEqualizer(9, listOf(2), definition)
    private fun eqPayload(eqName: String = name, id: Int = 9) =
        byteArrayOf(0, 1, 1, -6, 6, id.toByte(), eqName.length.toByte()) +
            eqName.toByteArray() + byteArrayOf(1, 0xE8.toByte(), 3, 0)
    private fun frame(command: Int, payload: ByteArray, canonical: Boolean = false): OppoWireCodec.Frame {
        val bytes = if (canonical) OppoWireCodec.canonicalPackets(command, payload = payload, frameBudget = 660).single()
            else OppoWireCodec.packet(command, payload = payload)
        return OppoWireCodec.Decoder().offer(bytes).single()
    }
    private fun budget(value: Int) = frame(0x8107, byteArrayOf(0, value.toByte(), (value ushr 8).toByte()))
    private fun offer(session: OppoSettingsSession, frame: OppoWireCodec.Frame) = session.offer(frame, 0x067410)

    @Test fun budgetOnlyDoesNotPublishSettingsOrHandshake() {
        val adapter = OppoEarbudAdapter()
        val received = adapter.receive(OppoWireCodec.packet(0x8107, payload = byteArrayOf(0, 100, 0)))
        assertNull(adapter.runtimeState().features.get<OppoSettingsFeatureState>())
        assertNull(received.handshake)
    }
    @Test fun emptySnapshotsRevokeSettingsCarrierButKeepConfirmedNoise() {
        val adapter = OppoEarbudAdapter()
        fun receive(command: Int, vararg p: Int) = adapter.receive(OppoWireCodec.packet(command, payload = p.map(Int::toByte).toByteArray()))
        receive(0x810D, 0, 1, 4, 0)
        assertEquals(OppoEarbudAdapter.SETTINGS_PRESENTATION_ID, adapter.miLinkCardPresentationId)
        receive(0x810D, 0, 0)
        assertNull(adapter.miLinkCardPresentationId)
        assertNotNull(adapter.runtimeState().features.get<OppoSettingsFeatureState>())
        adapter.receive(OppoWireCodec.packet(0x8122, payload = eqPayload("A")))
        assertEquals(OppoEarbudAdapter.SETTINGS_PRESENTATION_ID, adapter.miLinkCardPresentationId)
        receive(0x8122, 0, 0)
        assertNull(adapter.miLinkCardPresentationId)
        receive(0x810C, 1, 1, 4)
        assertEquals(OppoEarbudAdapter.PRESENTATION_ID, adapter.miLinkCardPresentationId)
        receive(0x810D, 0, 0)
        assertEquals(OppoEarbudAdapter.PRESENTATION_ID, adapter.miLinkCardPresentationId)
    }
    @Test fun longCustomWriteNeedsCanonicalListAndNegotiatedBudgetInEitherOrder() {
        for (budgetFirst in listOf(false, true)) {
            val session = OppoSettingsSession()
            assertTrue(session.initialQueries.any { it.contentEquals(OppoWireCodec.queryFrameBudget) })
            if (budgetFirst) offer(session, budget(100))
            val state = offer(session, frame(0x8122, eqPayload(), true))!!
            assertEquals(name, state.equalizers!!.single().name)
            assertEquals(budgetFirst, state.supports(request))
            if (!budgetFirst) {
                assertTrue(session.encode(request).isEmpty())
                offer(session, budget(100))
            }
            val packets = session.encode(request)
            assertTrue(packets.size > 1)
            assertTrue(packets.all { it.size <= 100 })
            val decoder = OppoWireCodec.Decoder()
            val decoded = packets.flatMap { decoder.offer(it) }.single()
            assertEquals(0x0418, decoded.command)
            assertEquals(2, decoded.payload.first().toInt())
            assertEquals(2, decoded.payload.last().toInt())
            // Writes and ACKs do not mutate confirmed gains.
            assertEquals(listOf(0), session.identify(0x067410)!!.equalizers!!.single().gains)
            assertNull(offer(session, frame(0x8418, byteArrayOf(0, 9))))
            session.reset()
            assertTrue(session.encode(request).isEmpty())
            offer(session, frame(0x8122, eqPayload(), true))
            assertTrue(session.encode(request).isEmpty())
        }
    }
    @Test fun legacyLongAndMalformedCanonicalReportsDoNotSelectLongWriteDialect() {
        val session = OppoSettingsSession()
        offer(session, budget(100))
        val state = offer(session, frame(0x8122, eqPayload()))!!
        assertFalse(state.canonicalLongFrames)
        assertTrue(session.encode(request).isEmpty())
        assertNull(offer(session, frame(0x8122, eqPayload() + byteArrayOf(99), true)))
        assertTrue(session.encode(request).isEmpty())
        offer(session, frame(0x8122, eqPayload(), true))
        assertFalse(session.encode(request).isEmpty())
        assertFalse(session.identify(0x123456)!!.supports(request))
    }
    @Test fun maximumNameAndBandDefinitionExceedsOldFrameLimitEndToEnd() {
        val eqName = "X".repeat(255)
        val frequencies = List(32) { 100 + it * 100 }
        val payload = byteArrayOf(0, 1, 1, -6, 6, 9, 255.toByte()) + eqName.toByteArray() +
            byteArrayOf(32) + frequencies.flatMap { listOf(it.toByte(), (it ushr 8).toByte(), 0.toByte()) }
        assertTrue(payload.size > 248)
        val session = OppoSettingsSession()
        offer(session, budget(100))
        val state = offer(session, frame(0x8122, payload, true))!!
        val eq = state.equalizers!!.single()
        val update = OppoControlRequest.UpdateEqualizer(9, List(32) { 2 }, eq.definition())
        assertTrue(state.supports(update))
        val decoder = OppoWireCodec.Decoder()
        val packets = session.encode(update)
        assertTrue(packets.all { it.size <= 100 })
        assertEquals(357, packets.flatMap { decoder.offer(it) }.single().payload.size)
        assertEquals(List(32) { 0 }, session.identify(0x067410)!!.equalizers!!.single().gains)
    }
    @Test fun completeLargeListParsesAcrossFragmentsButShortSlicesDoNotProveLongLength() {
        val payload = eqPayload()
        val packets = OppoWireCodec.canonicalPackets(0x8122, payload = payload, frameBudget = 100)
        val decoder = OppoWireCodec.Decoder()
        val session = OppoSettingsSession()
        offer(session, budget(100))
        assertTrue(decoder.offer(packets.first()).isEmpty())
        val state = offer(session, decoder.offer(packets.last()).single())!!
        assertEquals(name, state.equalizers!!.single().name)
        assertFalse(state.canonicalLongFrames)
        assertFalse(state.supports(request))
    }
    @Test fun reportedBudgetConstrainsShortWritesAndNoBudgetKeepsLegacyBytes() {
        val session = OppoSettingsSession()
        val entry = eqPayload("A")
        val short = OppoControlRequest.UpdateEqualizer(9, listOf(2), definition.copy(name = "A"))
        offer(session, frame(0x8122, entry))
        val legacy = session.encode(short).single()
        assertEquals(0xAA.toByte(), legacy[0])
        offer(session, budget(10))
        assertTrue(session.encode(short).isEmpty())
        // Invalid budgets never override confirmed limits.
        assertNull(offer(session, frame(0x8107, byteArrayOf(1, 100, 0))))
        assertTrue(session.encode(short).isEmpty())
        offer(session, budget(100))
        assertArrayEquals(legacy, session.encode(short).single())
    }
}
