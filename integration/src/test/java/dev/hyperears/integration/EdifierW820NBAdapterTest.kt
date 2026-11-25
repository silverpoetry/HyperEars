package dev.hyperears.integration

import dev.hyperears.protocol.edifier.EdifierV1WireCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdifierW820NBAdapterTest {

    @Test
    fun `retail name selects the V1 model adapter`() {
        val adapter = requireNotNull(
            EarbudAdapterRegistry.resolve(identity("EDIFIER W820NB")),
        )

        assertEquals(EdifierW820NBAdapter.ID, adapter.id)
        assertEquals(AdapterResolution.EXACT_MATCH, adapter.snapshot().resolution)
    }

    @Test
    fun `later revisions stay on the BES v2 family adapter`() {
        listOf("EDIFIER W820NB Plus", "EDIFIER W820NB Pro").forEach { name ->
            val adapter = requireNotNull(EarbudAdapterRegistry.resolve(identity(name)))
            assertEquals(name, EdifierHeadphonesAdapter.ID, adapter.id)
        }
    }

    @Test
    fun `handshake probes the V1 dialect instead of BES v2`() {
        val handshake = EdifierW820NBAdapter().beginHandshake()

        assertEquals(HandshakeResult.AwaitingEvidence, handshake.handshake)
        assertArrayEquals(
            listOf(
                EdifierV1WireCodec.queryBattery,
                EdifierV1WireCodec.queryAnc,
                EdifierV1WireCodec.queryFunction,
            ).toTypedArray(),
            handshake.commands.toTypedArray(),
        )
    }

    @Test
    fun `v2 payload is inert and a V1 battery report confirms the private channel`() {
        val adapter = EdifierW820NBAdapter()

        val ignored = adapter.receive(hex("AA EC D0 00 00 66"))
        assertEquals(null, ignored.handshake)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)

        val result = adapter.receive(hex("BB 02 D0 35 21 DB"))

        assertEquals(HandshakeResult.Ready, result.handshake)
        assertTrue(result.stateChanged)
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.snapshot().batterySource)
        assertEquals(
            53,
            adapter.runtimeState().features.get<BatteryFeatureState>()?.battery?.overall?.percent,
        )
    }

    @Test
    fun `valid anc report unlocks the verified three-state dialect`() {
        val adapter = EdifierW820NBAdapter()

        val result = adapter.receive(hex("BB 03 CC 02 06 21 AB"))

        assertEquals(HandshakeResult.Ready, result.handshake)
        assertTrue(adapter.snapshot().capabilities.noiseControl)
        assertFalse(adapter.snapshot().capabilities.windNoiseControl)
        assertEquals(
            setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
            adapter.snapshot().supportedNoiseModes,
        )
        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)
    }

    @Test
    fun `noise mode write uses the V1 set command after confirmation`() {
        val adapter = EdifierW820NBAdapter()
        adapter.receive(hex("BB 03 CC 02 06 21 AB"))

        val control = adapter.executeControl(
            StandardControlRequest.SetNoiseMode(NoiseMode.TRANSPARENCY),
        )

        assertTrue(control.accepted)
        assertArrayEquals(
            hex("AA 02 C1 03 21 89"),
            control.commands.single(),
        )
        assertArrayEquals(hex("AA 01 CC 21 90"), control.readback.single())
    }

    @Test
    fun `noise mode write is refused before protocol evidence`() {
        val adapter = EdifierW820NBAdapter()

        val control = adapter.executeControl(
            StandardControlRequest.SetNoiseMode(NoiseMode.ANC),
        )

        assertFalse(control.accepted)
        assertTrue(control.commands.isEmpty())
    }

    private fun identity(name: String): EarbudIdentity = EarbudIdentity(
        deviceName = name,
        standardHeadset = true,
        bluetoothDeviceClass = 0x0418,
    )

    private fun hex(value: String): ByteArray = value
        .trim()
        .split(' ')
        .map { it.toInt(16).toByte() }
        .toByteArray()
}
