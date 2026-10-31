package dev.hyperears.integration

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class RayNeoSessionTest {
    private fun deliver(session: RayNeoProtocolSession, cmd: String, data: String) =
        RayNeoLaunchCodec.fragment(buildJsonObject {
            put("uri", "ble.request.app_market")
            putJsonObject("payload") { put("cmd", cmd); put("data", data) }
        }.toString()).flatMap(session::offer)

    @Test fun discoveryGatesLaunchAndResetRemovesPermission() {
        val session = RayNeoProtocolSession()
        val launch = RayNeoControlRequest.LaunchApp(RayNeoQuickLaunch.BBLL)
        assertTrue(session.encode(launch).isEmpty())
        val events = deliver(session, "installed_apps", "[\"com.wayneshao.bbll\"]")
        assertTrue(events.contains(ProtocolEvent.HandshakeAccepted))
        assertFalse(session.encode(launch).isEmpty())
        assertTrue(session.encode(RayNeoControlRequest.LaunchApp(RayNeoQuickLaunch.MY_DV)).isEmpty())
        session.reset()
        assertTrue(session.encode(launch).isEmpty())
        assertTrue(deliver(session, "launch_status", "{\"pkg\":\"com.wayneshao.bbll\",\"status\":0}").isEmpty())
    }

    @Test fun ackAndOtherAppsDoNotCompleteOutstandingLaunch() {
        val session = RayNeoProtocolSession()
        deliver(session, "installed_apps", "[\"com.wayneshao.bbll\",\"com.github.mytv.dv\"]")
        session.encode(RayNeoControlRequest.LaunchApp(RayNeoQuickLaunch.BBLL))
        assertTrue(deliver(session, "ack", "launch").isEmpty())
        assertTrue(deliver(session, "launch_status", "{\"pkg\":\"com.github.mytv.dv\",\"status\":0}").isEmpty())
        assertTrue(session.encode(RayNeoControlRequest.LaunchApp(RayNeoQuickLaunch.MY_DV)).isEmpty())
        val result = deliver(session, "launch_status", "{\"pkg\":\"com.wayneshao.bbll\",\"status\":-1}")
            .filterIsInstance<ProtocolEvent.FeatureStateChanged>().single().state as RayNeoAppsFeatureState
        assertEquals(-1, result.lastLaunchStatus)
        assertEquals("com.wayneshao.bbll", result.lastLaunchPackage)
        assertFalse(session.encode(RayNeoControlRequest.LaunchApp(RayNeoQuickLaunch.MY_DV)).isEmpty())
    }

    @Test fun expiredResultsAreIgnoredAndRefreshDoesNotInventSuccess() {
        var now = 0L
        val session = RayNeoProtocolSession { now }
        deliver(session, "installed_apps", "[\"com.wayneshao.bbll\"]")
        session.encode(RayNeoControlRequest.LaunchApp(RayNeoQuickLaunch.BBLL))
        now = 6000
        assertTrue(deliver(session, "launch_status", "{\"pkg\":\"com.wayneshao.bbll\",\"status\":0}").isEmpty())
        assertFalse(session.encode(StandardControlRequest.Refresh).isEmpty())
        val state = deliver(session, "installed_apps", "[]").filterIsInstance<ProtocolEvent.FeatureStateChanged>().single().state as RayNeoAppsFeatureState
        assertNull(state.lastLaunchStatus)
        assertTrue(state.installedPackages.isEmpty())
    }
    @Test fun repeatedIdenticalLaunchResultsHaveDistinctSequences() {
        val session=RayNeoProtocolSession()
        deliver(session,"installed_apps","[\"com.wayneshao.bbll\"]")
        for(expected in 1L..2L) {
            assertFalse(session.encode(RayNeoControlRequest.LaunchApp(RayNeoQuickLaunch.BBLL)).isEmpty())
            val events=deliver(session,"launch_status","{\"pkg\":\"com.wayneshao.bbll\",\"status\":0}")
            val feature=events.filterIsInstance<ProtocolEvent.FeatureStateChanged>().single().state as RayNeoAppsFeatureState
            assertEquals(expected,feature.lastLaunchSequence)
        }
    }
}
