package dev.hyperears.integration
import dev.hyperears.protocol.sony.SonyDevicesCodec as Wire

internal class SonyDevicesSession {
    var state=SonyDevicesFeatureState();private set
    data class Update(val state:SonyDevicesFeatureState,val queries:List<ByteArray> = emptyList())
    fun reads(capability:Boolean=false):List<ByteArray> = buildList {
        state.deviceType?.let {type->
            if(capability || state.maxPaired==null)add(Wire.bytes(0x30,type))
            add(Wire.bytes(0x32,type));add(Wire.bytes(0x36,type))
        }
        if(0x31 in state.functions)add(Wire.bytes(0x36,1))
        if(0x34 in state.functions)add(Wire.bytes(0x36,3))
    }
    fun accept(payload:ByteArray):Update? {
        Wire.discovery(payload)?.let {functions->
            if(functions==state.functions)return null
            state=SonyDevicesFeatureState(functions,when{0x32 in functions->2;0x30 in functions->0;else->null})
            return Update(state,reads(true))
        }
        val report=Wire.parse(payload) ?: return null
        if(report is Wire.Toggle) {
            if(report.type==1 && 0x31 in state.functions)state=state.copy(sourceKeep=report.enabled)
            else if(report.type==3 && 0x34 in state.functions)state=state.copy(musicHandover=report.enabled)
            else return null
            return Update(state)
        }
        if(report.type!=state.deviceType)return null
        when(report) {
            is Wire.Capability -> {
                if(state.maxPaired==report.maxPaired && state.maxConnected==report.maxConnected)return null
                state=state.copy(maxPaired=report.maxPaired,maxConnected=report.maxConnected,devices=null,playbackRight=null,available=null,pairing=null)
                return Update(state,reads())
            }
            is Wire.Status -> state=state.copy(pairing=report.pairing,available=report.available)
            is Wire.Devices -> {
                val maxPaired=state.maxPaired ?: return null;val maxConnected=state.maxConnected ?: return null
                if(report.devices.size>maxPaired || report.devices.count{it.order>0}>maxConnected || report.devices.any{it.order>maxConnected}) {
                    state=state.copy(devices=null,playbackRight=null)
                } else state=state.copy(devices=report.devices.map{SonyPairedDevice(it.address,it.order,it.name,it.deviceClass)},playbackRight=report.playbackRight)
            }
            else -> return null
        }
        return Update(state)
    }
    fun encode(request:SonyControlRequest):List<ByteArray> {
        if(request !is SonyControlRequest.ManageDevices || !state.supports(request))return emptyList()
        val type=state.deviceType ?: return emptyList()
        val command=when(request.action) {
            SonyDeviceAction.CONNECT,SonyDeviceAction.DISCONNECT,SonyDeviceAction.FORGET ->
                Wire.bytes(0x3C,type,when(request.action){SonyDeviceAction.CONNECT->1;SonyDeviceAction.DISCONNECT->0;else->2})+request.address!!.toByteArray(Charsets.US_ASCII)
            SonyDeviceAction.PAIRING -> Wire.bytes(0x34,type,if(request.enabled==true)1 else 0,0)
            SonyDeviceAction.SOURCE_KEEP -> Wire.bytes(0x38,1,if(request.enabled==true)0 else 1)
            SonyDeviceAction.MUSIC_HANDOVER -> Wire.bytes(0x38,3,if(request.enabled==true)1 else 0)
            SonyDeviceAction.PLAYBACK_SOURCE -> Wire.bytes(0x3C,1)+request.address!!.toByteArray(Charsets.US_ASCII)
        }
        return listOf(command)+reads()
    }
}
