package dev.hyperears.integration

import org.junit.Assert.*
import org.junit.Test

class RayNeoX3ProAdapterTest {
    @Test fun realUncategorizedGlassesClassResolvesWithoutPretendingToBeAnEarbud() {
        val identity = EarbudIdentity("RayNeo X3 Pro-C062", false, bluetoothDeviceClass = 0x1F00)
        val adapter = EarbudAdapterRegistry.forIntegration(identity)
        assertTrue(adapter is RayNeoX3ProAdapter)
        assertFalse(adapter!!.supportsControl(RayNeoControlRequest.LaunchApp(RayNeoQuickLaunch.BBLL)))
        assertNull(EarbudAdapterRegistry.resolve(identity.copy(nativeSystemEarbud = true)))
        assertNull(EarbudAdapterRegistry.resolve(identity.copy(deviceName = "Unknown glasses")))
    }
    @Test fun completeRegistryStartsAndResolvesRayNeoWithLockedPrivateCapabilities() {
        EarbudAdapterRegistry.preloadCatalog()
        val adapter = EarbudAdapterRegistry.resolve(EarbudIdentity("RayNeo X3 Pro-C062",true))
        assertTrue(adapter is RayNeoX3ProAdapter)
        assertEquals(BatterySource.SYSTEM_AGGREGATE,adapter!!.snapshot().batterySource)
        assertFalse(adapter.supportsControl(RayNeoControlRequest.SetWifi(true,false,"CN")))
    }
    @Test fun candidatesDoNotGrantControlsBeforeAReply() {
        val adapter = RayNeoX3ProAdapter()
        assertFalse(adapter.supportsControl(RayNeoControlRequest.LaunchApp(RayNeoQuickLaunch.BBLL)))
        assertNull(adapter.miLinkCardPresentationId)
        val transport = adapter.transports.single() as GattTransportSpec
        assertEquals(256, transport.minimumMtu)
        assertEquals(256, transport.requestedMtu)
        assertTrue(transport.notificationsRequired)
        assertTrue(adapter.matches(EarbudIdentity("RayNeo X3 Pro-C062", true)))
        assertFalse(adapter.matches(EarbudIdentity("RayNeo X2", true)))
        assertFalse(adapter.matches(EarbudIdentity("RayNeo X3 Pro-C062", true, nativeSystemEarbud = true)))
        assertEquals(HeadsetFormFactor.GLASSES, adapter.formFactor)
        assertNull(adapter.artwork.chargingCase)
        assertEquals(listOf(ControlAppCatalog.rayneo), adapter.controlApps)
    }

    @Test fun confirmedPackagesControlLaunchAndSurviveStateSerialization() {
        val adapter = RayNeoX3ProAdapter()
        adapter.beginHandshake()
        val response = """{"uri":"ble.request.app_market","payload":{"cmd":"installed_apps","data":"[\"com.wayneshao.bbll\"]"}}"""
        RayNeoLaunchCodec.fragment(response).forEach(adapter::receive)
        val request = RayNeoControlRequest.LaunchApp(RayNeoQuickLaunch.BBLL)
        assertTrue(adapter.supportsControl(request))
        assertTrue(adapter.executeControl(request).commands.isNotEmpty())
        val features = adapter.runtimeState().features
        assertEquals(features, FeatureStateTransport.decode(FeatureStateTransport.encode(features)))
        adapter.resetProtocolSession()
        assertNull(adapter.runtimeState().features.get<RayNeoAppsFeatureState>())
        assertFalse(adapter.supportsControl(request))
    }
}
