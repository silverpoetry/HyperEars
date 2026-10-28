package dev.hyperears.integration

import dev.hyperears.protocol.huawei.HuaweiFreebudsSppCodec as Wire
import org.junit.Assert.*
import org.junit.Test

class HuaweiPodsCatalogTest {
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun adapter(name: String) = requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(name, true)))

    @Test fun exactRoutesHaveDiscoveryCandidatesWithoutInferredControls() {
        val names = listOf("FreeBuds 4E", "FreeBuds 5", "FreeBuds 6", "FreeBuds 7", "FreeBuds 7i", "FreeArc", "Eyewear", "Eyewear 2")
        val adapters = names.map { adapter("HUAWEI $it") }
        assertEquals(8, adapters.map { it.id }.toSet().size)
        adapters.forEach {
            assertEquals(AdapterResolution.EXACT_MATCH, it.resolution)
            assertTrue(it.transports.first() is RfcommEndpointSpec.ServiceUuid)
            assertEquals((1..8).toList(), it.transports.drop(1).map { endpoint -> (endpoint as RfcommEndpointSpec.Channel).number })
            assertFalse(it.snapshot().capabilities.noiseControl)
            assertFalse(it.supportsControl(HuaweiDailyControlRequest.SetWearDetection(true)))
        }
        assertEquals(adapter("HUAWEI Eyewear").id, adapter("HUAWEI Eyewear 3").id)
        assertNotEquals(adapter("HUAWEI FreeBuds 7").id, adapter("HUAWEI FreeBuds 7i").id)
        assertFalse(adapters.any { it.matches(EarbudIdentity("HUAWEI FreeBuds 7 Pro", true)) })
        assertEquals("huawei-freebuds-4", adapter("HUAWEI FreeBuds 4").id)
    }

    // Literal TLV/CRC frames derived independently from HuaweiPods' profile tables and
    // packetWithCrc; CRC cross-check uses Python binascii.crc_hqx(frameWithoutCrc, 0).
    @Test fun sevenIHasBidirectionalOwnLevelMapAndReportConfirmation() {
        val a = adapter("HUAWEI FreeBuds 7i")
        val report = hex("5A0007002B2A010201013621")
        a.receive(report)
        assertEquals(HuaweiAncLevel.DYNAMIC, a.runtimeState().features.get<HuaweiAncLevelFeatureState>()!!.current)
        val expected = mapOf(HuaweiAncLevel.COMFORT to "5A0007002B0401020100E11C",
            HuaweiAncLevel.DYNAMIC to "5A0007002B0401020101F13D",
            HuaweiAncLevel.NORMAL to "5A0007002B0401020102C15E",
            HuaweiAncLevel.ULTRA to "5A0007002B0401020103D17F")
        expected.forEach { (level, bytes) ->
            val execution = a.executeControl(HuaweiControlRequest.SetAncLevel(level))
            assertTrue(execution.accepted)
            assertArrayEquals(hex(bytes), execution.commands.single())
            assertArrayEquals(Wire.queryNoiseState, execution.readback.single())
            assertEquals(HuaweiAncLevel.DYNAMIC, a.runtimeState().features.get<HuaweiAncLevelFeatureState>()!!.current)
        }
        a.receive(hex("5A0007002B2A010203015043"))
        assertEquals(HuaweiAncLevel.ULTRA, a.runtimeState().features.get<HuaweiAncLevelFeatureState>()!!.current)
        a.receive(hex("5A0007002B2A01020401C9D4"))
        assertFalse(a.supportsControl(HuaweiControlRequest.SetAncLevel(HuaweiAncLevel.DYNAMIC)))
        a.receive(report)
        a.resetProtocolSession()
        assertFalse(a.supportsControl(HuaweiControlRequest.SetAncLevel(HuaweiAncLevel.DYNAMIC)))
        val pro3 = adapter("HUAWEI FreeBuds Pro 3")
        pro3.receive(report)
        assertEquals(HuaweiAncLevel.COMFORT, pro3.runtimeState().features.get<HuaweiAncLevelFeatureState>()!!.current)
    }

    @Test fun semiOpenAndFourKeepRestrictedLevelsAndNoTransparency() {
        listOf("4", "4E", "5", "6", "7").forEach { model ->
            val a = adapter("HUAWEI FreeBuds $model")
            a.receive(hex("5A0007002B2A010200010510"))
            val state = a.runtimeState().features.get<HuaweiAncLevelFeatureState>()!!
            assertEquals(HuaweiAncLevel.NORMAL, state.current)
            assertFalse(HuaweiAncLevel.ULTRA in state.supported)
            assertEquals(model !in listOf("4", "4E"), HuaweiAncLevel.DYNAMIC in state.supported)
            assertEquals(if (model == "7") setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY)
                else setOf(NoiseMode.ANC, NoiseMode.OFF), a.snapshot().supportedNoiseModes)
            if (model == "7") {
                val write = a.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.TRANSPARENCY))
                assertArrayEquals(byteArrayOf(2, 2), Wire.parseFrame(write.commands.single())!!.params[1])
                a.receive(hex("5A0007002B2A010202025311"))
                assertEquals(HuaweiAncLevel.TRANS_NORMAL, a.runtimeState().features.get<HuaweiAncLevelFeatureState>()!!.current)
            }
        }
    }

    @Test fun existingSixIAddsLevelsWithoutChangingMapping() {
        val a = adapter("HUAWEI FreeBuds 6i")
        a.receive(hex("5A0007002B2A010203015043"))
        assertEquals(HuaweiAncLevel.DYNAMIC, a.runtimeState().features.get<HuaweiAncLevelFeatureState>()!!.current)
        assertTrue(a.supportsControl(HuaweiControlRequest.SetAncLevel(HuaweiAncLevel.ULTRA)))
        a.receive(hex("5A0007002B2A010202025311"))
        assertEquals(HuaweiAncLevel.TRANS_NORMAL, a.runtimeState().features.get<HuaweiAncLevelFeatureState>()!!.current)
        assertFalse(a.supportsControl(HuaweiControlRequest.SetAncLevel(HuaweiAncLevel.ULTRA)))
        assertTrue(a.supportsControl(HuaweiControlRequest.SetAncLevel(HuaweiAncLevel.VOICE_BOOST)))
    }

    @Test fun noAncAccessoriesKeepTwoEarBatteryAndEyewearHasNoCase() {
        listOf("FreeArc", "Eyewear", "Eyewear 2").forEach { name ->
            val a = adapter("HUAWEI $name")
            assertFalse(a.beginHandshake().commands.any { Wire.parseFrame(it)!!.command == Wire.CMD_NOISE_STATE })
            a.receive(hex("5A0007002B2A010201013621"))
            assertFalse(a.snapshot().capabilities.noiseControl)
            assertNull(a.runtimeState().features.get<HuaweiAncLevelFeatureState>())
            a.receive(hex("5A00080001080203465A64320D"))
            assertEquals(70, a.runtimeState().battery.left.percent)
            assertEquals(90, a.runtimeState().battery.right.percent)
            assertNull(a.runtimeState().battery.overall.percent)
            assertEquals(if (name == "FreeArc") 100 else null, a.runtimeState().battery.case.percent)
        }
        val glasses = adapter("HUAWEI Eyewear")
        glasses.receive(hex("5A00070001080202465A2D05"))
        assertEquals(70, glasses.runtimeState().battery.left.percent)
        assertEquals(90, glasses.runtimeState().battery.right.percent)
        assertNull(Wire.parseBatteryFrame(hex("5A00070001080202465A2D05")))
    }

    @Test fun dailyFalseValuesAndSpecialFourHoldAreReportedNotStatic() {
        val a = adapter("HUAWEI FreeBuds 4E")
        a.receive(hex("5A0006002B11010100CFC3"))
        assertEquals(false, a.runtimeState().features.get<HuaweiDailySettingsFeatureState>()!!.wearDetection)
        assertTrue(a.supportsControl(HuaweiDailyControlRequest.SetWearDetection(true)))
        a.receive(hex("5A0006002B17010103D839"))
        val request = HuaweiDailyControlRequest.SetGesture(HuaweiGestureKind.HOLD, HuaweiGestureSide.LEFT, 14)
        assertArrayEquals(hex("5A0006002B1601010E7F20"), a.executeControl(request).commands.single())
        assertFalse(a.supportsControl(request.copy(value = 10)))
        val glasses = adapter("HUAWEI Eyewear 2")
        glasses.receive(hex("5A0006000120010101CBA5"))
        assertTrue(glasses.supportsControl(HuaweiDailyControlRequest.SetGesture(HuaweiGestureKind.DOUBLE, HuaweiGestureSide.LEFT, 0)))
        assertFalse(glasses.supportsControl(HuaweiDailyControlRequest.SetGesture(HuaweiGestureKind.DOUBLE, HuaweiGestureSide.LEFT, 2)))
        assertFalse(glasses.supportsControl(HuaweiDailyControlRequest.SetWearDetection(true)))
    }
}
