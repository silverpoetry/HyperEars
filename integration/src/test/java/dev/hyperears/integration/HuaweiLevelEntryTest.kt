package dev.hyperears.integration

import dev.hyperears.protocol.huawei.HuaweiFreebudsSppCodec
import org.junit.Assert.*
import org.junit.Test

class HuaweiLevelEntryTest {
    @Test fun levelReportAloneOpensSettingsAndInvalidLevelRevokesEntry() {
        val adapter = requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(deviceName = "HUAWEI FreeBuds Pro 3", standardHeadset = true)))
        fun report(level: Int, mode: Int) = adapter.receive(HuaweiFreebudsSppCodec.packet(0x2B2A, listOf(1 to byteArrayOf(level.toByte(), mode.toByte()))))
        report(0, 1)
        assertNull(adapter.runtimeState().features.get<HuaweiDailySettingsFeatureState>())
        assertEquals(HuaweiDailyPresentationIds.SETTINGS, adapter.snapshot().presentationId)
        val request = HuaweiControlRequest.SetAncLevel(HuaweiAncLevel.ULTRA)
        assertTrue(adapter.executeControl(request).accepted)
        report(127, 1)
        assertNull(adapter.snapshot().presentationId)
        assertFalse(adapter.executeControl(request).accepted)
        report(0, 1)
        assertTrue(adapter.executeControl(request).accepted)
        report(1, 2)
        assertEquals(HuaweiDailyPresentationIds.SETTINGS, adapter.snapshot().presentationId)
        report(0, 0)
        assertNull(adapter.snapshot().presentationId)
    }
}
