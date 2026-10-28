package dev.hyperears.integration

import dev.hyperears.protocol.huawei.HuaweiFreebudsSppCodec as Wire
import org.junit.Assert.*
import org.junit.Test

class HuaweiAdditionalModelsTest {
    private fun adapter(name: String) = requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(deviceName = "HUAWEI FreeBuds $name", standardHeadset = true)))
    @Test fun seModelsDoNotAcquireNoiseFromForeignReportsAndUseTheirOwnQueries() {
        for (name in listOf("SE", "SE 2")) {
            val device = adapter(name)
            assertTrue(device.id.startsWith("huawei-freebuds-se"))
            device.receive(Wire.packet(0x2B2A, listOf(1 to byteArrayOf(0, 1))))
            assertFalse(device.effectiveCapabilities().noiseControl)
            assertNull(device.runtimeState().features.get<HuaweiAncLevelFeatureState>())
        }
        assertEquals(16, HuaweiDailyModelCatalog.models.single { it.name == "HUAWEI FreeBuds SE" }.channel)
        assertEquals(setOf(HuaweiGestureKind.DOUBLE), HuaweiDailyProfile.SE.gestures.keys)
    }
    @Test fun se2HoldCallUsesFieldFourAndNeverPublishesUnsupportedSides() {
        val device = adapter("SE 2")
        device.receive(Wire.packet(0x2B17, listOf(1 to byteArrayOf(10), 2 to byteArrayOf(10), 4 to byteArrayOf(0))))
        val settings = device.runtimeState().features.get<HuaweiDailySettingsFeatureState>()!!
        val hold = settings.gestures.single()
        assertNull(hold.left); assertNull(hold.right); assertEquals(0, hold.call)
        assertFalse(settings.supports(HuaweiDailyControlRequest.SetGesture(HuaweiGestureKind.HOLD, HuaweiGestureSide.LEFT, 10)))
        val request = HuaweiDailyControlRequest.SetGesture(HuaweiGestureKind.HOLD, HuaweiGestureSide.CALL, -1)
        assertTrue(settings.supports(request))
        val frame = Wire.parseFrame(device.protocolSession.encode(request).single())!!
        assertEquals(0x2B16, frame.command)
        assertArrayEquals(byteArrayOf(-1), frame.params[4])
        assertFalse(frame.params.containsKey(2))
    }
    @Test fun qualifiedNewModelsExposeOnlyReportedLevelsAndDailyHandlers() {
        val se4 = adapter("SE 4 ANC")
        val pro5 = adapter("Pro 5")
        for (device in listOf(se4, pro5)) {
            assertNull(device.runtimeState().features.get<HuaweiAncLevelFeatureState>())
            device.receive(Wire.packet(0x2B2A, listOf(1 to byteArrayOf(3, 1))))
            assertEquals(HuaweiAncLevel.DYNAMIC, device.runtimeState().features.get<HuaweiAncLevelFeatureState>()!!.current)
        }
        assertFalse(HuaweiAncLevel.VOICE_BOOST in se4.runtimeState().features.get<HuaweiAncLevelFeatureState>()!!.supported)
        pro5.receive(Wire.packet(0x2B2A, listOf(1 to byteArrayOf(1, 2))))
        assertEquals(HuaweiAncLevel.VOICE_BOOST, pro5.runtimeState().features.get<HuaweiAncLevelFeatureState>()!!.current)
        se4.receive(Wire.packet(0x2BA3, listOf(2 to byteArrayOf(1))))
        assertNull(se4.runtimeState().features.get<HuaweiDailySettingsFeatureState>())
        pro5.receive(Wire.packet(0x2BA3, listOf(2 to byteArrayOf(1))))
        assertEquals(true, pro5.runtimeState().features.get<HuaweiDailySettingsFeatureState>()!!.preferSoundQuality)
    }
}
