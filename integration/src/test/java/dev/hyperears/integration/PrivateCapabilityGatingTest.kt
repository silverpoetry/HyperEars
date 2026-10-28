package dev.hyperears.integration

import dev.hyperears.protocol.nicehck.NiceHckWireCodec
import dev.hyperears.protocol.oppo.OppoWireCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateCapabilityGatingTest {
    @Test
    fun directlyMatchedPrivateModelsStartFromTheStandardProjection() {
        val candidates = listOf(
            VivoTwsAir3ProAdapter(),
            VivoTws3eAdapter(),
            OppoEncoAir2ProAdapter(),
            OppoEncoFree4Adapter(),
            OppoEncoX3Adapter(),
            OppoEncoAir5Adapter(),
            StarRingUltraAdapter(),
            EdifierW860NBProAdapter(),
            EdifierEvoProAdapter(),
            RoseEarfreeI5Adapter(),
            RoseBudsFeelMk2Adapter(),
            NiceHckYuanDaoOrigAdapter(),
            AppleAirPodsProAdapter(),
            AppleAirPodsMaxAdapter(),
        )

        candidates.forEach { adapter ->
            val snapshot = adapter.snapshot()
            assertTrue(adapter.id, adapter.privateProtocolRequired)
            assertEquals(adapter.id, TransportReadiness.PROTOCOL_HANDSHAKE, adapter.transportReadiness)
            assertEquals(adapter.id, BatterySource.SYSTEM_AGGREGATE, snapshot.batterySource)
            assertTrue(adapter.id, snapshot.capabilities.battery)
            assertTrue(adapter.id, snapshot.capabilities.audioHandoff)
            assertFalse(adapter.id, snapshot.capabilities.noiseControl)
            assertFalse(adapter.id, snapshot.capabilities.windNoiseControl)
            assertTrue(adapter.id, snapshot.supportedNoiseModes.isEmpty())
            assertEquals(adapter.id, null, snapshot.presentationId)
            assertFalse(
                adapter.id,
                adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)),
            )
        }
    }

    @Test
    fun vivoExactModelUnlocksModesOnlyFromAValidModeReport() {
        val adapter = VivoTwsAir3ProAdapter()

        val result = adapter.receive(hex("FF 03 00 04 00 1B 82 30 00 01 04 00"))

        assertEquals(HandshakeResult.Ready, result.handshake)
        assertTrue(adapter.snapshot().capabilities.noiseControl)
        assertEquals(NoiseMode.OFF, adapter.runtimeState().noiseMode)
        assertEquals(THREE_STATE_MODES, adapter.snapshot().supportedNoiseModes)
    }

    @Test
    fun oppoExactModelUnlocksModesOnlyFromAValidAncReport() {
        var adapter: EarbudAdapter = OppoEncoAir2ProAdapter()
        val response = OppoWireCodec.packet(
            command = OppoWireCodec.ANC_RESPONSE,
            payload = hex("00 01 01 01"),
        )

        adapter.receive(response)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        adapter = (adapter.receive(OppoWireCodec.packet(0x8103, payload = hex("00 10 38 06")))
            .handshake as HandshakeResult.Replace).adapter
        adapter.receive(response)

        assertTrue(adapter.snapshot().capabilities.noiseControl)
        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)
        assertEquals(THREE_STATE_MODES, adapter.snapshot().supportedNoiseModes)
    }

    @Test
    fun starRingExactModelUnlocksItsCardAndModesFromAValidReport() {
        val adapter = StarRingUltraAdapter()

        val result = adapter.receive(
            hex("09 FF 00 00 01 06 02 0E 00 00 00 01 00 20"),
        )

        assertEquals(HandshakeResult.Ready, result.handshake)
        assertTrue(adapter.snapshot().capabilities.noiseControl)
        assertTrue(adapter.snapshot().capabilities.windNoiseControl)
        assertEquals(StarRingUltraAdapter.PRESENTATION_ID, adapter.snapshot().presentationId)
        assertEquals(NoiseMode.WIND, adapter.runtimeState().noiseMode)
    }

    @Test
    fun niceHckExactModelUnlocksItsCardAndModesFromAValidReport() {
        val adapter = NiceHckYuanDaoOrigAdapter()

        val result = adapter.receive(
            NiceHckWireCodec.command(0x0101, byteArrayOf(0x11)),
        )

        assertEquals(HandshakeResult.Ready, result.handshake)
        assertTrue(adapter.snapshot().capabilities.noiseControl)
        assertTrue(adapter.snapshot().capabilities.windNoiseControl)
        assertEquals(NiceHckYuanDaoOrigAdapter.ADVANCED_PRESENTATION_ID, adapter.snapshot().presentationId)
        assertEquals(NoiseMode.WIND, adapter.runtimeState().noiseMode)
    }

    @Test
    fun roseExactModelsUnlockTheirCardsAndModesFromValidReports() {
        val earfree = RoseEarfreeI5Adapter()
        val budsFeel = RoseBudsFeelMk2Adapter()

        assertEquals(HandshakeResult.Ready, earfree.receive(earfreeNoiseResponse()).handshake)
        assertEquals(HandshakeResult.Ready, budsFeel.receive(budsFeelStatusResponse()).handshake)

        listOf(earfree, budsFeel).forEach { adapter ->
            assertTrue(adapter.id, adapter.snapshot().capabilities.noiseControl)
            assertTrue(adapter.id, adapter.snapshot().capabilities.windNoiseControl)
            assertEquals(adapter.id, NoiseMode.WIND, adapter.runtimeState().noiseMode)
            assertTrue(adapter.id, adapter.snapshot().presentationId != null)
        }
    }

    private fun earfreeNoiseResponse(): ByteArray {
        val payload = byteArrayOf(0, 0, 1, 0)
        val size = 10 + payload.size
        val body = byteArrayOf(
            0x09,
            0xFF.toByte(),
            0,
            0,
            1,
            0x06,
            0x02,
            size.toByte(),
            0,
        ) + payload
        return body + byteArrayOf(body.checksum())
    }

    private fun budsFeelStatusResponse(): ByteArray {
        val body = byteArrayOf(
            0xDD.toByte(),
            0x2A,
            0x15,
            0x04,
            0x0C,
            91,
            82,
            67,
            0x02,
            0x09,
            0x04,
        ) + ByteArray(13)
        return body + byteArrayOf(body.checksum(), 0xAA.toByte())
    }

    private fun ByteArray.checksum(): Byte =
        sumOf { it.toInt() and 0xFF }.and(0xFF).toByte()

    private fun hex(value: String): ByteArray = value
        .trim()
        .split(Regex("\\s+"))
        .map { it.toInt(16).toByte() }
        .toByteArray()

    private companion object {
        val THREE_STATE_MODES = setOf(
            NoiseMode.ANC,
            NoiseMode.OFF,
            NoiseMode.TRANSPARENCY,
        )
    }
}
