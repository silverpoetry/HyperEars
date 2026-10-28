package dev.hyperears.widget

import dev.hyperears.integration.*
import dev.hyperears.presentation.NativeAdmission
import dev.hyperears.presentation.NativePresentationMetadata
import java.util.Locale

internal enum class BatteryWidgetStatus { CONNECTED, DISCONNECTED, EXTERNAL, SYSTEM, UNCONFIRMED }

/** Display-only data. No Binder, identity token, command, or trusted state is persisted. */
internal data class BatteryWidgetRecord(
    val address:String,
    val name:String,
    val battery:EarbudBattery,
    val formFactor:HeadsetFormFactor,
    val status:BatteryWidgetStatus,
    val recordedAtMillis:Long?,
    val connectionOrder:Long=0,
    val verified:Boolean=false,
) {
    val hasBattery get()=listOf(battery.left,battery.right,battery.case,battery.overall).any {it.available}
}

internal object BatteryWidgetProjection {
    const val MAX_DEVICES=32
    fun address(value:String?):String?=value?.uppercase(Locale.ROOT)?.takeIf {it.matches(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}"))}
    fun from(state:EarbudState,metadata:NativePresentationMetadata,recordedAt:Long?,verified:Boolean):BatteryWidgetRecord? {
        val address=address(state.address)?:return null
        val status=when {
            metadata.admission==NativeAdmission.SYSTEM -> BatteryWidgetStatus.SYSTEM
            metadata.admission!=NativeAdmission.HYPEREARS -> BatteryWidgetStatus.UNCONFIRMED
            state.lifecycle.controlOwnership==ControlOwnership.EXTERNAL_APP -> BatteryWidgetStatus.EXTERNAL
            !state.sessionActive || state.lifecycle.systemProfile!=SystemProfileState.CONNECTED -> BatteryWidgetStatus.DISCONNECTED
            else -> BatteryWidgetStatus.CONNECTED
        }
        val raw=if(status in setOf(BatteryWidgetStatus.SYSTEM,BatteryWidgetStatus.UNCONFIRMED)) EarbudBattery() else state.battery
        val battery=if(raw.overall.available && state.adapter?.batterySource!=BatterySource.PRIVATE_PROTOCOL)
            EarbudBattery(overall=raw.overall,case=raw.case) else raw
        return BatteryWidgetRecord(address,(state.deviceName?:state.adapter?.displayName?:address).take(128),battery,
            state.adapter?.formFactor?:HeadsetFormFactor.TWS,status,recordedAt.takeIf {listOf(battery.left,battery.right,battery.case,battery.overall).any {it.available}},
            metadata.connection.connectedAtElapsedMs,verified)
    }
    fun select(records:Collection<BatteryWidgetRecord>,fixedAddress:String?):BatteryWidgetRecord? {
        if(fixedAddress!=null)return records.firstOrNull {it.address==address(fixedAddress)}
        return records.filter {it.verified && it.status==BatteryWidgetStatus.CONNECTED}
            .sortedWith(compareByDescending<BatteryWidgetRecord>{it.connectionOrder}.thenBy{it.address}).firstOrNull()
            ?: records.sortedWith(compareByDescending<BatteryWidgetRecord>{it.recordedAtMillis?:0}.thenBy{it.address}).firstOrNull()
    }
}

/** Producer-side bounded projection dedup. Motion/revision-only reports never produce an emission. */
internal class BatteryWidgetProjector {
    private data class Scope(val producer:Any,val connection:String,val connectedAt:Long,val token:String)
    private data class Cached(val scope:Scope,val record:BatteryWidgetRecord,val revision:Long,val admission:NativeAdmission,val scopedOwnership:Boolean)
    private val records=linkedMapOf<String,Cached>()
    @Synchronized fun accept(state:EarbudState,token:String,metadata:NativePresentationMetadata,producer:Any,replay:Boolean,nowMillis:Long):BatteryWidgetRecord? {
        var next=BatteryWidgetProjection.from(state,metadata,null,true)?:return null
        val scope=Scope(producer,metadata.connection.id,metadata.connection.connectedAtElapsedMs,token)
        val previous=records[next.address]
        if(previous?.scope==scope && state.revision<previous.revision)return null
        if(previous!=null && metadata.connection.connectedAtElapsedMs<previous.scope.connectedAt)return null
        if(next.status==BatteryWidgetStatus.DISCONNECTED && !next.hasBattery && previous?.record?.hasBattery==true &&
            previous.scope==scope) next=next.copy(battery=previous.record.battery)
        val time=when {
            !next.hasBattery -> null
            previous?.record?.battery==next.battery -> previous.record.recordedAtMillis
            !replay -> nowMillis.takeIf {it>0}
            else -> null
        }
        val recorded=next.copy(recordedAtMillis=time)
        val cached=Cached(scope,recorded,state.revision,metadata.admission,metadata.connectionScopedOwnership)
        records.remove(next.address);records[next.address]=cached
        while(records.size>BatteryWidgetProjection.MAX_DEVICES)records.remove(records.keys.first())
        return recorded.takeIf {replay || previous==null || previous.scope!=scope || previous.record!=recorded ||
            previous.admission!=metadata.admission || previous.scopedOwnership!=metadata.connectionScopedOwnership}
    }
    @Synchronized fun clear(){records.clear()}
    internal fun size()=records.size
}
