package dev.hyperears.integration

import dev.hyperears.protocol.huawei.HuaweiFreebudsSppCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HUAWEI FreeClip (first generation) has no noise control hardware.
 *
 * On-device logs show the firmware still mirrors `2B 04` / `2B 2A` mode state, so the
 * battery-only profile must never turn that echo into a published capability.
 */
class HuaweiFreeClipGen1AdapterTest {
    private val adapter = HuaweiFreeClipAdapter()

    @Test
    fun registrySelectsTheFirstGenerationExactly() {
        assertTrue(
            EarbudAdapterRegistry.resolve(identity("HUAWEI FreeClip")) is HuaweiFreeClipAdapter,
        )
        assertTrue(
            EarbudAdapterRegistry.resolve(identity("FreeClip")) is HuaweiFreeClipAdapter,
        )
        assertFalse(
            EarbudAdapterRegistry.resolve(identity("HUAWEI FreeClip 2")) is HuaweiFreeClipAdapter,
        )
        assertFalse(
            EarbudAdapterRegistry.resolve(identity("HUAWEI FreeBuds Pro 3")) is
                HuaweiFreeClipAdapter,
        )
    }

    @Test
    fun exactAdapterUsesChannelOneOnly() {
        assertEquals(AdapterResolution.EXACT_MATCH, adapter.snapshot().resolution)
        assertTrue(adapter.snapshot().privateProtocolRequired)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertTrue(adapter.snapshot().supportedNoiseModes.isEmpty())

        val channel = adapter.transports.single() as RfcommEndpointSpec.Channel
        assertEquals(1, channel.number)
    }

    @Test
    fun handshakeAndRefreshSendOnlyTheBatteryQuery() {
        assertEquals(
            listOf(HuaweiFreebudsSppCodec.queryBattery.toList()),
            adapter.beginHandshake().commands.map(ByteArray::toList),
        )

        val refresh = adapter.executeControl(StandardControlRequest.Refresh)
        assertTrue(refresh.accepted)
        assertEquals(
            listOf(HuaweiFreebudsSppCodec.queryBattery.toList()),
            refresh.commands.map(ByteArray::toList),
        )
        assertTrue(adapter.queryState(NoiseModeFeatureState.FEATURE_ID).isEmpty())
    }

    @Test
    fun batteryEvidenceConfirmsComponentBatteryWithoutNoiseModes() {
        val result = adapter.receive(batteryFrame())

        assertEquals(HandshakeResult.Ready, result.handshake)
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.snapshot().batterySource)
        val battery = adapter.runtimeState().battery
        assertEquals(16, battery.left.percent)
        assertEquals(32, battery.right.percent)
        assertEquals(48, battery.case.percent)
        assertEquals(64, battery.overall.percent)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
    }

    @Test
    fun firmwareModeEchoCannotOpenNoiseControl() {
        val echo = adapter.receive(
            HuaweiFreebudsSppCodec.packet(
                0x2B2A,
                listOf(1 to byteArrayOf(0x03, 0x01)),
            ),
        )

        assertTrue(echo.commands.isEmpty())
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertTrue(adapter.snapshot().supportedNoiseModes.isEmpty())
        NoiseMode.entries.forEach { mode ->
            assertFalse(
                adapter.executeControl(StandardControlRequest.SetNoiseMode(mode)).accepted,
            )
        }
    }

    private fun batteryFrame(): ByteArray = HuaweiFreebudsSppCodec.packet(
        0x0108,
        listOf(
            1 to byteArrayOf(0x40),
            2 to byteArrayOf(0x10, 0x20, 0x30),
            3 to byteArrayOf(0x00, 0x01, 0x00),
        ),
    )

    private fun identity(name: String): EarbudIdentity = EarbudIdentity(
        deviceName = name,
        standardHeadset = true,
    )
}
