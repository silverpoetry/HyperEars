package dev.hyperears.integration

import dev.hyperears.protocol.rayneo.RayNeoBleWireCodec
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RayNeoReplyTest {
    private fun reply(cmd: String, data: String) = buildJsonObject {
        put("uri", "ble.request.app_market")
        putJsonObject("payload") { put("cmd", cmd); put("data", data) }
    }.toString()

    @Test fun installedAppsIsStringArrayNotVersionObjects() {
        val packages = "[\"com.wayneshao.bbll\",\"com.rayneo.airscreen\"]"
        assertEquals(setOf("com.wayneshao.bbll", "com.rayneo.airscreen"),
            (RayNeoLaunchCodec.parse(reply("installed_apps", packages)) as RayNeoLaunchReply.InstalledApps).packages)
        assertNull(RayNeoLaunchCodec.parse(reply("installed_apps", "[{\"pkg\":\"com.wayneshao.bbll\"}]")))
    }

    @Test fun ackNeverBecomesLaunchSuccess() {
        assertNull(RayNeoLaunchCodec.parse(reply("ack", "launch")))
        for (status in -1..1) {
            val parsed = RayNeoLaunchCodec.parse(reply("launch_status", "{\"pkg\":\"com.wayneshao.bbll\",\"status\":$status}"))
            assertEquals(RayNeoLaunchReply.LaunchStatus("com.wayneshao.bbll", status), parsed)
        }
        assertNull(RayNeoLaunchCodec.parse(reply("launch_status", "{\"pkg\":\"com.wayneshao.bbll\",\"status\":2}")))
        assertNull(RayNeoLaunchCodec.parse(reply("installed_apps", "[]").replace("ble.request", "ble.response")))
    }

    @Test fun unicodeFragmentationAndReset() {
        val message = reply("installed_apps", "[\"com.wayneshao.bbll\"]") + " ".repeat(180) + "\n"
        val decoder = RayNeoBleWireCodec.Decoder()
        val packets = RayNeoBleWireCodec.encode(message)
        assertTrue(packets.size > 1)
        assertNull(decoder.offer(packets.first()))
        assertEquals(message, decoder.offer(packets.last()))
        decoder.offer(packets.first())
        decoder.reset()
        assertNull(decoder.offer(byteArrayOf(2, 65)))
        assertEquals("眼镜", decoder.offer(RayNeoBleWireCodec.encode("眼镜").single()))
    }

    @Test fun rejectsOversizedAndMalformedStreamsAndRecovers() {
        val decoder = RayNeoBleWireCodec.Decoder(maxBytes = 300)
        assertNull(decoder.offer(ByteArray(253) { if(it == 0) 1 else 65 }))
        assertNull(decoder.offer(ByteArray(253) { if(it == 0) 2 else 65 }))
        assertNull(decoder.offer(byteArrayOf(0, 65))) // Discard remainder after overflow.
        assertNull(decoder.offer(byteArrayOf(9, 65)))
        assertNull(decoder.offer(byteArrayOf(0, 0xc3.toByte(), 0x28)))
        assertEquals("ok", decoder.offer(RayNeoBleWireCodec.encode("ok").single()))
    }
}
