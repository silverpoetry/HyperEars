package dev.hyperears.integration
import org.junit.Assert.*
import org.junit.Test
class RayNeoInstalledAppTest {
    @Test fun onlyPackagesReportedByCurrentGlassesCanBeLaunched() {
        val session=RayNeoProtocolSession()
        val request=RayNeoControlRequest.LaunchInstalledApp("com.mb.android")
        assertTrue(session.encode(request).isEmpty())
        RayNeoLaunchCodec.fragment("""{"uri":"ble.request.app_market","payload":{"cmd":"installed_apps","data":"[\"com.mb.android\"]"}}""").forEach(session::offer)
        assertTrue(session.encode(RayNeoControlRequest.LaunchInstalledApp("com.unknown.app")).isEmpty())
        assertFalse(session.encode(request).isEmpty())
        assertEquals(request,ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
        session.reset()
        assertTrue(session.encode(request).isEmpty())
    }
}
