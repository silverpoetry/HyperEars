package dev.hyperears.integration

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RayNeoSettingsTest {
    @Test fun requestsRoundTripAndRequireMatchingConfirmedState() {
        val state = RayNeoSettingsFeatureState(true,true,true,30)
        val requests = listOf(RayNeoControlRequest.SetWifi(true,false,"CN"),
            RayNeoControlRequest.SetDeepSuspend(true,false), RayNeoControlRequest.SetAutoLock(30,60))
        requests.forEach {
            assertEquals(it,ControlRequestTransport.decode(ControlRequestTransport.encode(it)))
            assertTrue(state.supports(it))
            assertFalse(state.copy(verifiedX3Pro=false).supports(it))
        }
        assertFalse(state.supports(RayNeoControlRequest.SetWifi(false,true,"CN")))
        assertFalse(state.supports(RayNeoControlRequest.SetWifi(true,false,"CNA")))
        assertFalse(state.supports(RayNeoControlRequest.SetAutoLock(15,60)))
        assertFalse(state.copy(deepSuspend=false).supports(RayNeoControlRequest.SetAutoLock(30,60)))
        assertEquals(state,FeatureStateTransport.decode(FeatureStateTransport.encode(DeviceFeatureSnapshot(listOf(state))))?.get<RayNeoSettingsFeatureState>())
    }
    private fun deliver(session: RayNeoProtocolSession, message: String) =
        RayNeoLaunchCodec.fragment(message).flatMap(session::offer)
    @Test fun deepSuspendRefreshesSnapshotBeforeWritingAndExpiresWithoutMutation() {
        var now = 0L
        val session = RayNeoProtocolSession { now }
        val request = RayNeoControlRequest.SetDeepSuspend(false,true)
        assertTrue(session.encode(request).isEmpty())
        deliver(session,"""{"uri":"ble.request.general.settings","generalSettings":{"deviceType":"RayNeoX3Pro","productModel":"ARGF20","autoLockTime":-1}}""")
        val status = """{"uri":"ble.request.general.status","generalStatus":{"deepSuspend":0,"brightness":42}}"""
        deliver(session,status)
        assertFalse(session.encode(request).isEmpty())
        assertTrue(session.drainImmediateCommands().isEmpty())
        deliver(session,status)
        val decoder = dev.hyperears.protocol.rayneo.RayNeoBleWireCodec.Decoder()
        val writes = session.drainImmediateCommands().mapNotNull(decoder::offer)
        assertEquals(2,writes.size)
        assertEquals(JsonPrimitive(42),Json.parseToJsonElement(writes.first()).jsonObject["generalStatus"]!!.jsonObject["brightness"])
        session.encode(request)
        now = 6000
        deliver(session,status)
        assertTrue(session.drainImmediateCommands().isEmpty())
        session.reset()
        assertTrue(session.encode(request).isEmpty())
    }
    @Test fun actualDeviceStatusUsesOneBatteryAndDoesNotInventMissingFields() {
        val status = RayNeoSettingsCodec.parse("""{"uri":"ble.request.general.status","generalStatus":{"battery":95,"isCharging":true,"wifiEnabled":true,"deepSuspend":0}}""") as RayNeoSettingsReply.Status
        assertEquals(95, status.battery?.percent)
        assertEquals(true, status.battery?.charging)
        assertEquals(true, status.wifiEnabled)
        assertEquals(false, status.deepSuspend)
        val missing = RayNeoSettingsCodec.parse("""{"uri":"ble.request.general.status","generalStatus":{"battery":101,"wifiEnabled":"true"}}""") as RayNeoSettingsReply.Status
        assertNull(missing.battery)
        assertNull(missing.wifiEnabled)
        assertNull(missing.deepSuspend)
    }
    @Test fun settingsValidateModelAndKeepNeverDistinctFromUnknown() {
        val settings = RayNeoSettingsCodec.parse("""{"uri":"ble.request.general.settings","generalSettings":{"deviceType":"RayNeoX3Pro","productModel":"ARGF20","autoLockTime":-1}}""") as RayNeoSettingsReply.Settings
        assertTrue(settings.x3Pro)
        assertEquals(-1, settings.autoLockSeconds)
        assertNull(RayNeoSettingsCodec.parse("""{"uri":"ble.request.general.settings","generalSettings":[]} """))
    }
    @Test fun fullStatusUpdatePreservesUnknownFieldsAndRequiresExistingValue() {
        val source = buildJsonObject { put("deepSuspend",0); put("brightness",42); put("futureField","keep") }
        val encoded = Json.parseToJsonElement(RayNeoSettingsCodec.deepSuspend(source,true)).jsonObject
        val changed = encoded.getValue("generalStatus").jsonObject
        assertEquals(source.keys,changed.keys)
        assertEquals(JsonPrimitive(1),changed["deepSuspend"])
        assertEquals(source["futureField"],changed["futureField"])
        assertEquals(source["brightness"],changed["brightness"])
    }
}
