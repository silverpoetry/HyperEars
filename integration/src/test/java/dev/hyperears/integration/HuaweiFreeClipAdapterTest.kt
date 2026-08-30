package dev.hyperears.integration

import dev.hyperears.protocol.huawei.HuaweiFreebudsSppCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HuaweiFreeClipAdapterTest {
    private val adapter = HuaweiFreeClip2Adapter()

    @Test
    fun registryResolvesOnlyTheHardwareVerifiedClipModelExactly() {
        assertTrue(
            EarbudAdapterRegistry.resolve(identity("HUAWEI FreeClip 2")) is
                HuaweiFreeClip2Adapter,
        )
        assertTrue(
            EarbudAdapterRegistry.resolve(identity("FreeClip 2")) is HuaweiFreeClip2Adapter,
        )
        assertTrue(
            EarbudAdapterRegistry.resolve(identity("HUAWEI FreeClip")) is
                HuaweiFreebudsFamilyAdapter,
        )
        assertTrue(
            EarbudAdapterRegistry.resolve(identity("FreeClip")) is HuaweiFreebudsFamilyAdapter,
        )
        assertFalse(
            EarbudAdapterRegistry.resolve(identity("HUAWEI FreeBuds Pro 3")) is
                HuaweiFreeClip2Adapter,
        )
    }

    @Test
    fun exactAdapterStartsFromTheLockedStandardProjection() {
        assertEquals(AdapterResolution.EXACT_MATCH, adapter.snapshot().resolution)
        assertEquals(HeadsetFormFactor.TWS, adapter.snapshot().formFactor)
        assertNull(adapter.snapshot().presentationId)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertTrue(adapter.snapshot().capabilities.battery)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        assertTrue(adapter.snapshot().privateProtocolRequired)
        assertTrue(adapter.snapshot().supportedNoiseModes.isEmpty())
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
        assertEquals(
            listOf(HuaweiFreebudsSppCodec.queryBattery.toList()),
            adapter.queryState(BatteryFeatureState.FEATURE_ID).map(ByteArray::toList),
        )
        assertTrue(adapter.queryState(NoiseModeFeatureState.FEATURE_ID).isEmpty())
    }

    @Test
    fun validBatteryEvidenceConfirmsTheSessionAndPromotesComponentBattery() {
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
    fun noiseFramesCannotConfirmOrDriveTheBatteryOnlyProfile() {
        val noiseState = adapter.receive(
            HuaweiFreebudsSppCodec.packet(
                0x2B2A,
                listOf(1 to byteArrayOf(0x00, 0x01)),
            ),
        )
        val noiseNotify = adapter.receive(
            HuaweiFreebudsSppCodec.packet(0x2B03, emptyList()),
        )

        assertNull(noiseState.handshake)
        assertNull(noiseNotify.handshake)
        assertTrue(noiseState.commands.isEmpty())
        assertTrue(noiseNotify.commands.isEmpty())
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertTrue(adapter.snapshot().supportedNoiseModes.isEmpty())
        assertNull(adapter.runtimeState().noiseMode)
    }

    @Test
    fun noiseModeWritesAreAlwaysRejected() {
        NoiseMode.entries.forEach { mode ->
            assertFalse(
                adapter.executeControl(StandardControlRequest.SetNoiseMode(mode)).accepted,
            )
        }
    }

    @Test
    fun protocolResetRestoresSystemBatteryUntilFreshEvidenceArrives() {
        adapter.onSystemBatteryChanged(72)
        adapter.receive(batteryFrame())
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.snapshot().batterySource)

        adapter.resetProtocolSession()
        adapter.onSystemBatteryChanged(72)

        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        assertEquals(72, adapter.runtimeState().battery.overall.percent)
        assertNull(adapter.runtimeState().battery.case.percent)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
    }

    @Test
    fun privateChannelFailureUsesTheExistingDormantRecoveryPath() {
        assertEquals(
            InitialProtocolFailureResolution.KeepDormant,
            adapter.onInitialProtocolUnavailable(),
        )
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
