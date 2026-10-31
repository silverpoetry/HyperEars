package dev.hyperears.integration

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RayNeoWifiTest {
    @Test fun actualFirmwareScanOmitsNullableBssidAndConnectMustPreserveThatShape() {
        val reply = RayNeoWifiCodec.parse("""{"uri":"ble.request.scan_wifi_result","scanResults":[{"ssid":"Example","capabilities":"[ESS]","frequency":2412,"level":-50,"saved":false}]}""") as RayNeoWifiReply.Scan
        val network = reply.networks.single()
        assertNull(network.bssid)
        val wire = Json.parseToJsonElement(RayNeoWifiCodec.connect(RayNeoControlRequest.ConnectWifi(network,null))).jsonObject
        assertFalse(wire.getValue("wifi").jsonObject.containsKey("bssid"))
        assertFalse(wire.containsKey("password"))
    }
    private fun offer(session: RayNeoProtocolSession, text: String) = RayNeoLaunchCodec.fragment(text).flatMap(session::offer)
    @Test fun scanStopsOnReplyOrDeadlineAndResetForgetsAccessPoints() {
        val session = RayNeoProtocolSession()
        val scan = RayNeoControlRequest.ScanWifi("CN")
        assertTrue(session.encode(scan).isEmpty())
        offer(session,"""{"uri":"ble.request.general.settings","generalSettings":{"deviceType":"RayNeoX3Pro","productModel":"ARGF20"}}""")
        offer(session,"""{"uri":"ble.request.general.status","generalStatus":{"wifiEnabled":true}}""")
        assertFalse(session.encode(scan).isEmpty())
        assertTrue(session.encode(scan).isEmpty())
        offer(session,"""{"uri":"ble.request.scan_wifi_result","scanResults":[]}""")
        assertFalse(session.drainImmediateCommands().isEmpty())
        assertTrue(session.query(TelemetryQuery.RefreshFeature(RayNeoWifiFeatureState.STOP_QUERY)).isEmpty())
        assertFalse(session.encode(scan).isEmpty())
        assertFalse(session.query(TelemetryQuery.RefreshFeature(RayNeoWifiFeatureState.STOP_QUERY)).isEmpty())
        assertTrue(session.query(TelemetryQuery.RefreshFeature(RayNeoWifiFeatureState.STOP_QUERY)).isEmpty())
        session.reset()
        assertTrue(session.encode(scan).isEmpty())
        assertTrue(session.drainImmediateCommands().isEmpty())
    }
    @Test fun scanKeepsAccessPointIdentityAndConnectionStatusIsNotGuessed() {
        val reply = RayNeoWifiCodec.parse("""{"uri":"ble.request.scan_wifi_result","scanResults":[{"ssid":"Test","bssid":"00:11:22:33:44:55","capabilities":"[WPA2-PSK-CCMP][ESS]","frequency":5180,"level":-40,"saved":false}]}""") as RayNeoWifiReply.Scan
        assertEquals(1,reply.networks.size)
        assertTrue(reply.networks.single().requiresPassword)
        assertTrue(reply.networks.single().canConnect)
        val request = RayNeoControlRequest.ConnectWifi(reply.networks.single(),"example-pass")
        assertEquals(request,ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
        assertFalse(request.toString().contains("example-pass"))
        val root = Json.parseToJsonElement(RayNeoWifiCodec.connect(request)).jsonObject
        assertEquals(JsonPrimitive("00:11:22:33:44:55"),root["wifi"]!!.jsonObject["bssid"])
        assertEquals(JsonPrimitive("example-pass"),root["password"])
        assertNull(RayNeoWifiCodec.parse("""{"uri":"ble.request.wifi.connect_state","ssid":"Test","state":"made_up"}"""))
    }
    @Test fun malformedNetworksAreRejectedAndEnterpriseIsNotTreatedAsOpen() {
        assertNull(RayNeoWifiCodec.parse("""{"uri":"ble.request.scan_wifi_result","scanResults":[{"ssid":"Test"}]}"""))
        val enterprise = RayNeoWifiNetwork("Test","00:11:22:33:44:55","[WPA2-EAP-CCMP]",5180,-40,false)
        assertFalse(enterprise.canConnect)
        assertTrue(enterprise.copy(saved=true).canConnect)
        assertFalse(enterprise.copy(capabilities="[SAE]").canConnect)
    }
}
