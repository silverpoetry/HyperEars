package dev.hyperears.integration
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Transient

@Serializable enum class SonyDeviceAction {CONNECT,DISCONNECT,FORGET,PAIRING,SOURCE_KEEP,MUSIC_HANDOVER,PLAYBACK_SOURCE}
@Serializable data class SonyPairedDevice(val address:String,val order:Int,val name:String,val deviceClass:Int?=null)
@Serializable @SerialName("sony.devices")
data class SonyDevicesFeatureState(val functions:Set<Int> = emptySet(),val deviceType:Int?=null,
    val maxPaired:Int?=null,val maxConnected:Int?=null,val available:Boolean?=null,val pairing:Boolean?=null,
    val devices:List<SonyPairedDevice>?=null,val playbackRight:Int?=null,
    val sourceKeep:Boolean?=null,val musicHandover:Boolean?=null):DeviceFeatureState {
    @Transient override val featureId=FEATURE_ID
    val hasSettings get()=deviceType!=null && devices!=null && maxConnected!=null && maxConnected>0
    fun supports(request:SonyControlRequest):Boolean {
        if(request !is SonyControlRequest.ManageDevices || request.baseline!=this || !hasSettings || available!=true)return false
        val device=devices?.singleOrNull{it.address==request.address}
        return when(request.action) {
            SonyDeviceAction.CONNECT -> device?.order==0 && devices!!.count{it.order>0}<maxConnected!!
            SonyDeviceAction.DISCONNECT -> device!=null && device.order>0
            SonyDeviceAction.FORGET -> device!=null
            SonyDeviceAction.PAIRING -> pairing!=null && request.enabled!=null && request.enabled!=pairing
            SonyDeviceAction.SOURCE_KEEP -> 0x31 in functions && sourceKeep!=null && request.enabled!=null && request.enabled!=sourceKeep
            SonyDeviceAction.MUSIC_HANDOVER -> 0x34 in functions && musicHandover!=null && request.enabled!=null && request.enabled!=musicHandover
            SonyDeviceAction.PLAYBACK_SOURCE -> 0x31 in functions && sourceKeep!=null && device!=null && device.order>0 && device.order!=playbackRight
        }
    }
    companion object {const val FEATURE_ID="sony.devices"}
}
