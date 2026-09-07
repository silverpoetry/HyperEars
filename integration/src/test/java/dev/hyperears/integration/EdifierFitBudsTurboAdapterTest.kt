package dev.hyperears.integration

import dev.hyperears.protocol.edifier.EdifierWireCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdifierFitBudsTurboAdapterTest {
    @Test
    fun exactNamesSelectTheModelWithoutClaimingAdjacentProducts() {
        listOf("EDIFIER FitBuds Turbo", "FitBuds Turbo").forEach { name ->
            assertEquals(EdifierFitBudsTurboAdapter.ID, EarbudAdapterRegistry.resolve(identity(name))?.id)
        }
        val adapter = EdifierFitBudsTurboAdapter()
        listOf("FitBuds", "FitClip Ultra", "EDIFIER FitBuds Turbo 2").forEach { name ->
            assertFalse(adapter.matches(identity(name)))
        }
        assertFalse(adapter.matches(identity("FitBuds Turbo").copy(nativeSystemEarbud = true)))
        assertFalse(adapter.matches(identity("FitBuds Turbo").copy(standardHeadset = false)))
    }

    @Test
    fun batteryNoiseAndGameUnlockIndependentlyInEitherResponseOrder() {
        val adapter = EdifierFitBudsTurboAdapter()
        adapter.beginHandshake()
        assertNull(adapter.snapshot().presentationId)
        assertFalse(adapter.supportsControl(EdifierControlRequest.SetGameMode(true)))
        assertFalse(adapter.snapshot().capabilities.noiseControl)

        adapter.receive(reply(0xF2, 3, 100, 98, 0, 3, 0x11))
        assertEquals(100, adapter.runtimeState().battery.left.percent)
        assertEquals(98, adapter.runtimeState().battery.right.percent)
        assertNull(adapter.snapshot().presentationId)

        adapter.receive(reply(0xCC, 0x1B, 6))
        assertEquals(NoiseMode.entries.toSet(), adapter.snapshot().supportedNoiseModes)
        assertEquals(EdifierMiLinkPresentationIds.FITBUDS_TURBO, adapter.snapshot().presentationId)
        assertFalse(adapter.supportsControl(EdifierControlRequest.SetGameMode(true)))

        adapter.receive(reply(0x08, 0))
        assertTrue(adapter.supportsControl(EdifierControlRequest.SetGameMode(true)))
        assertEquals(EdifierMiLinkPresentationIds.FITBUDS_TURBO, adapter.snapshot().presentationId)

        adapter.resetProtocolSession()
        assertNull(adapter.snapshot().presentationId)
        assertNull(adapter.runtimeState().features.get<EdifierGameModeFeatureState>())
        assertFalse(adapter.supportsControl(EdifierControlRequest.SetGameMode(true)))
        adapter.beginHandshake()
        adapter.receive(reply(0x08, 1))
        assertEquals(EdifierMiLinkPresentationIds.GAME_MODE, adapter.snapshot().presentationId)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        adapter.receive(reply(0xCC, 0x1B, 4))
        assertEquals(EdifierMiLinkPresentationIds.FITBUDS_TURBO, adapter.snapshot().presentationId)
    }

    @Test
    fun invalidOrDifferentDialectReportsCannotUnlockPrivateControls() {
        val adapter = EdifierFitBudsTurboAdapter()
        adapter.beginHandshake()
        listOf(
            reply(0xCC, 0x10, 1), reply(0xCC, 0x1B, 255),
            reply(0xCC, 0xBE, 0xA3), reply(0x08, 2), reply(0x08, 1, 0),
            reply(0x08, 1).also { it[it.lastIndex] = 0 },
        ).forEach(adapter::receive)
        assertNull(adapter.snapshot().presentationId)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertFalse(adapter.executeControl(EdifierControlRequest.SetGameMode(true)).accepted)
    }

    @Test
    fun controlsUsePlaintextWritesAndReadOnlyReadback() {
        val adapter = EdifierFitBudsTurboAdapter()
        adapter.beginHandshake()
        adapter.receive(reply(0xCC, 0x1B, 6))
        adapter.receive(reply(0x08, 0))
        mapOf(NoiseMode.ANC to 1, NoiseMode.WIND to 4, NoiseMode.TRANSPARENCY to 5, NoiseMode.OFF to 6)
            .forEach { (mode, value) ->
                val result = adapter.executeControl(StandardControlRequest.SetNoiseMode(mode))
                assertTrue(result.accepted)
                val expected = EdifierWireCodec.packet(0xC1, byteArrayOf(0x1B, value.toByte()))
                assertArrayEquals(expected, result.commands.single())
                assertArrayEquals(EdifierWireCodec.queryAnc, result.readback.single())
                adapter.receive(reply(0xCC, 0x1B, value))
                assertEquals(mode, adapter.runtimeState().noiseMode)
            }
        val game = adapter.executeControl(EdifierControlRequest.SetGameMode(true))
        assertArrayEquals(EdifierWireCodec.packet(0x09, byteArrayOf(1)), game.commands.single())
        assertArrayEquals(EdifierWireCodec.queryGameState, game.readback.single())
        assertEquals(false, adapter.runtimeState().features.get<EdifierGameModeFeatureState>()?.enabled)
        adapter.receive(reply(0x09, 1))
        assertEquals(true, adapter.runtimeState().features.get<EdifierGameModeFeatureState>()?.enabled)
    }

    @Test
    fun plaintextBatteryClearsAbsentEarsAndKeepsTheCase() {
        val adapter = EdifierFitBudsTurboAdapter()
        adapter.receive(reply(0xF2, 3, 100, 98, 0, 3, 0x11))
        adapter.receive(reply(0xF2, 3, 0, 0, 70, 1, 0x11))
        assertEquals(EarbudBattery(case = BatteryReading(70, true)), adapter.runtimeState().battery)
    }

    private fun identity(name: String) = EarbudIdentity(deviceName = name, standardHeadset = true)

    private fun reply(command: Int, vararg payload: Int): ByteArray {
        val body = byteArrayOf(0xBB.toByte(), 0xEC.toByte(), command.toByte(), 0, payload.size.toByte()) +
            payload.map(Int::toByte).toByteArray()
        return body + body.sumOf { it.toInt() and 0xFF }.toByte()
    }
}
