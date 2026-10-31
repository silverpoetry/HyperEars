package dev.hyperears.integration
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Transient

@Serializable data class SonyQuickAccessAction(val id:Int,val default:Int,val services:List<Int>)
@Serializable data class SonyQuickAccessDefinition(val key:Int,val type:Int,val actions:List<SonyQuickAccessAction>)
/** Sound Connect SAR catalog; an unselected service need not appear in the slot capability. */
object SonyQuickAccessServices {
    val names=linkedMapOf(0 to "无功能",1 to "Spotify",2 to "Endel",3 to "Amazon Music",4 to "腾讯小微",
        5 to "喜马拉雅",6 to "酷狗音乐",7 to "QQ 音乐",8 to "Eye Navi",9 to "网易云音乐",10 to "Apple Music",12 to "YouTube Music")
    fun candidates(action:SonyQuickAccessAction,current:Int)=(names.keys+action.services+action.default+current).distinct()
}
@Serializable @SerialName("sony.quick_access")
data class SonyQuickAccessFeatureState(val definition:SonyQuickAccessDefinition?=null,
    val current:List<Int>?=null,val available:Boolean?=null):DeviceFeatureState {
    @Transient override val featureId=FEATURE_ID
    val hasSettings get()=definition?.actions?.isNotEmpty()==true && current?.size==definition.actions.size
    fun supports(request:SonyControlRequest):Boolean = request is SonyControlRequest.SetQuickAccess &&
        hasSettings && available==true && request.expected==definition && request.baseline==current &&
        request.index in definition!!.actions.indices && request.service in 0..255 &&
        request.service in SonyQuickAccessServices.candidates(definition.actions[request.index],current!![request.index]) &&
        request.service!=current[request.index]
    companion object {const val FEATURE_ID="sony.quick_access"}
}
