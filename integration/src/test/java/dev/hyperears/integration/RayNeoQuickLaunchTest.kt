package dev.hyperears.integration

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RayNeoQuickLaunchTest {
    @Test fun defaultsMatchInstalledAppsInRequestedOrder() {
        assertEquals(listOf("BBLL", "myDV", "红果TV", "浮窗"), RayNeoQuickLaunch.entries.map { it.label })
        assertEquals(listOf("com.wayneshao.bbll", "com.github.mytv.dv", "com.hongguotv.rayneo", "com.rayneo.airscreen"),
            RayNeoQuickLaunch.entries.map { it.packageName })
    }

    @Test fun launchEnvelopeMatchesOfficialAppMarketProtocol() {
        val id = "00000000-0000-0000-0000-000000000001"
        for (app in RayNeoQuickLaunch.entries) {
            val root = Json.parseToJsonElement(RayNeoLaunchCodec.message(app, id)).jsonObject
            assertEquals("ble.response.app_market", root.getValue("uri").jsonPrimitive.content)
            assertEquals(id, root.getValue("id").jsonPrimitive.content)
            val payload = root.getValue("payload").jsonObject
            assertEquals("launch", payload.getValue("cmd").jsonPrimitive.content)
            val data = Json.parseToJsonElement(payload.getValue("data").jsonPrimitive.content).jsonObject
            assertEquals(app.packageName, data.getValue("pkg").jsonPrimitive.content)
            assertFalse(data.containsKey("extras"))
            assertFalse(root.containsKey("device"))
        }
    }

    @Test fun fixedSizeFragmentsMatchOfficialBoundaryRules() {
        for ((length, markers) in listOf(1 to listOf(0), 252 to listOf(0), 253 to listOf(1, 0), 505 to listOf(1, 2, 0))) {
            val packets = RayNeoLaunchCodec.fragment("x".repeat(length))
            assertEquals(markers, packets.map { it[0].toInt() })
            assertTrue(packets.all { it.size == 253 })
            val joined = packets.flatMap { it.drop(1) }.toByteArray()
            assertEquals("x".repeat(length), joined.copyOf(length).toString(Charsets.UTF_8))
            assertTrue(joined.drop(length).all { it == 0.toByte() })
        }
    }

    @Test fun launchRequestSurvivesControlTransport() {
        val request = RayNeoControlRequest.LaunchApp(RayNeoQuickLaunch.FLOATING_WINDOW)
        assertEquals(request, ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
    }
}
