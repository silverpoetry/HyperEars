package dev.hyperears.integration

import dev.hyperears.protocol.huawei.HuaweiFreebudsSppCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HuaweiFreeClipAdapterTest {

    private val freeClip = HuaweiFreeClipAdapter()
    private val freeClip2 = HuaweiFreeClip2Adapter()

    @Test
    fun registryResolvesClipModelsByNormalizedName() {
        assertTrue(EarbudAdapterRegistry.resolve(identity("HUAWEI FreeClip 2")) is HuaweiFreeClip2Adapter)
        assertTrue(EarbudAdapterRegistry.resolve(identity("FreeClip 2")) is HuaweiFreeClip2Adapter)
        assertTrue(EarbudAdapterRegistry.resolve(identity("HUAWEI FreeClip")) is HuaweiFreeClipAdapter)
        assertTrue(EarbudAdapterRegistry.resolve(identity("FreeClip")) is HuaweiFreeClipAdapter)
        assertFalse(EarbudAdapterRegistry.resolve(identity("HUAWEI FreeBuds Pro 3")) is HuaweiFreeClip2Adapter)
    }

    @Test
    fun clipAdaptersStartWithLockedStandardCapabilities() {
        listOf(freeClip, freeClip2).forEach { adapter ->
            assertEquals(AdapterResolution.EXACT_MATCH, adapter.snapshot().resolution)
            assertEquals(HeadsetFormFactor.TWS, adapter.snapshot().formFactor)
            assertNull(adapter.snapshot().presentationId)
            assertFalse(adapter.snapshot().capabilities.noiseControl)
            assertTrue(adapter.snapshot().capabilities.battery)
            assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
            assertTrue(adapter.snapshot().privateProtocolRequired)
            assertTrue(adapter.snapshot().supportedNoiseModes.isEmpty())
        }
    }

    @Test
    fun clipHandshakeSendsOnlyTheBatteryQuery() {
        val commands = freeClip2.beginHandshake().commands
        assertEquals(
            listOf(HuaweiFreebudsSppCodec.queryBattery.toList()),
            commands.map(ByteArray::toList),
        )
    }

    @Test
    fun clipBatteryEvidencePromotesPrivateSourceAndConfirmsHandshake() {
        val frame = HuaweiFreebudsSppCodec.packet(
            0x0108,
            listOf(
                1 to byteArrayOf(0x40),
                2 to byteArrayOf(0x10, 0x20, 0x30),
                3 to byteArrayOf(0x00, 0x01, 0x00),
            ),
        )

        val result = freeClip2.receive(frame)

        assertEquals(HandshakeResult.Ready, result.handshake)
        assertEquals(BatterySource.PRIVATE_PROTOCOL, freeClip2.snapshot().batterySource)
        val battery = freeClip2.runtimeState().battery
        assertEquals(16, battery.left.percent)
        assertEquals(32, battery.right.percent)
        assertEquals(48, battery.case.percent)
        assertEquals(64, battery.overall.percent)
    }

    @Test
    fun clipNoiseFrameDoesNotOpenNoiseControl() {
        val result = freeClip2.receive(
            HuaweiFreebudsSppCodec.packet(0x2B2A, listOf(1 to byteArrayOf(0x00, 0x01))),
        )

        assertEquals(HandshakeResult.Ready, result.handshake)
        assertFalse(freeClip2.snapshot().capabilities.noiseControl)
        assertTrue(freeClip2.snapshot().supportedNoiseModes.isEmpty())
        assertNull(freeClip2.runtimeState().noiseMode)
    }

    @Test
    fun clipRejectsNoiseModeWrites() {
        assertFalse(freeClip2.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)).accepted)
        assertFalse(freeClip.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.OFF)).accepted)
    }

    @Test
    fun clipRefreshEncodesBatteryQueryOnly() {
        val result = freeClip2.executeControl(StandardControlRequest.Refresh)
        assertTrue(result.accepted)
        assertEquals(
            listOf(HuaweiFreebudsSppCodec.queryBattery.toList()),
            result.commands.map(ByteArray::toList),
        )
    }

    @Test
    fun clipRemainsDormantAfterBoundedFailure() {
        assertEquals(InitialProtocolFailureResolution.KeepDormant, freeClip2.onInitialProtocolUnavailable())
        assertEquals(InitialProtocolFailureResolution.KeepDormant, freeClip.onInitialProtocolUnavailable())
    }

    private fun identity(name: String): EarbudIdentity =
        EarbudIdentity(deviceName = name, standardHeadset = true)
}
