package dev.hyperears.integration

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RayNeoLevelsTest {
    @Test fun volumeSlidersRoundTripAllActualHardwareSteps() {
        for(setting in listOf(RayNeoLevelSetting.MEDIA_VOLUME,RayNeoLevelSetting.SYSTEM_VOLUME)) {
            for(index in 0..15) {
                val sent = setting.wireValue(index)
                val applied = kotlin.math.floor(sent*15/100.0+0.5).toInt()
                val reported = applied*100/15
                assertEquals(index,applied)
                assertEquals(index,setting.displayValue(reported))
            }
        }
        assertEquals(55,RayNeoLevelSetting.BRIGHTNESS.wireValue(55))
    }
    private val snapshot = Json.parseToJsonElement("""{"brightness":40,"automaticBrightness":false,"volumes":{"1":30,"3":60,"0":25},"wifiEnabled":false,"deepSuspend":0,"unknown":99}""").jsonObject
    @Test fun brightnessAndVolumeChangeOnlySelectedFields() {
        val changed = Json.parseToJsonElement(RayNeoSettingsCodec.level(snapshot,RayNeoLevelSetting.BRIGHTNESS,55)).jsonObject.getValue("generalStatus").jsonObject
        assertEquals(JsonPrimitive(55),changed["brightness"])
        assertEquals(snapshot - "brightness",changed - "brightness")
        val volume = Json.parseToJsonElement(RayNeoSettingsCodec.level(snapshot,RayNeoLevelSetting.MEDIA_VOLUME,45)).jsonObject.getValue("generalStatus").jsonObject
        assertEquals(JsonPrimitive(45),volume["volumes"]!!.jsonObject["3"])
        assertEquals(snapshot["volumes"]!!.jsonObject - "3",volume["volumes"]!!.jsonObject - "3")
        assertEquals(snapshot - "volumes",volume - "volumes")
    }
    @Test fun automaticBrightnessAndStaleValuesGateManualRequests() {
        val state = RayNeoSettingsFeatureState(verifiedX3Pro=true,brightness=40,automaticBrightness=false,mediaVolume=60)
        val request = RayNeoControlRequest.SetLevel(RayNeoLevelSetting.BRIGHTNESS,40,55)
        assertTrue(state.supports(request))
        assertFalse(state.copy(automaticBrightness=true).supports(request))
        assertFalse(state.copy(automaticBrightness=null).supports(request))
        assertFalse(state.copy(brightness=50).supports(request))
        assertFalse(state.supports(request.copy(value=101)))
        assertEquals(request,ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
        val automatic = RayNeoControlRequest.SetAutomaticBrightness(false,true)
        assertTrue(state.supports(automatic))
        assertEquals(automatic,ControlRequestTransport.decode(ControlRequestTransport.encode(automatic)))
    }
    @Test fun invalidOrMissingTelemetryDoesNotBecomeZero() {
        val reply = RayNeoSettingsCodec.parse("""{"uri":"ble.request.general.status","generalStatus":{"brightness":101,"automaticBrightness":"true","volumes":{"3":-1,"1":20}}}""") as RayNeoSettingsReply.Status
        assertNull(reply.brightness)
        assertNull(reply.automaticBrightness)
        assertNull(reply.mediaVolume)
        assertEquals(20,reply.systemVolume)
    }
}
