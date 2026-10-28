package dev.hyperears.integration

import org.junit.Assert.*
import org.junit.Test

class AppleSettingsPresentationTest {
    @Test fun settingsOnlyUsesItsOwnPresentationWithoutInventingListening() {
        val adapter = AppleAirPodsAdapter()
        assertNull(adapter.miLinkCardPresentationId)
        adapter.receive(byteArrayOf(4, 0, 4, 0, 9, 0, 1, 0, 0, 0, 0))
        assertEquals(MiLinkCardPresentationId("apple-settings"), adapter.miLinkCardPresentationId)
        assertNull(adapter.runtimeState().features.get<AppleListeningModeFeatureState>())
        assertFalse(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))
        assertFalse(adapter.supportsControl(AppleControlRequest.SetListeningMode(AppleListeningMode.OFF)))
        adapter.receive(byteArrayOf(4, 0, 4, 0, 9, 0, 0x0d, 2, 0, 0, 0))
        assertEquals(AppleMiLinkPresentationIds.LISTENING, adapter.miLinkCardPresentationId)
        adapter.resetProtocolSession()
        assertNull(adapter.miLinkCardPresentationId)
        adapter.receive(byteArrayOf(4, 0, 4, 0, 9, 0, 1, 2, 0, 0, 0))
        assertEquals(MiLinkCardPresentationId("apple-settings"), adapter.miLinkCardPresentationId)
    }
}
