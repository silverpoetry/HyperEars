package dev.hyperears.widget

import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.hyperears.bridge.*
import dev.hyperears.hook.MiLinkDeviceOwnershipRegistry
import dev.hyperears.integration.BatteryFeatureState
import dev.hyperears.presentation.NativeAdmission

/** Volatile authentication state. Persisted records never enter these guards. */
internal class BatteryWidgetStateStore(
    initial:List<BatteryWidgetRecord> = emptyList(),
    private val changed:(List<BatteryWidgetRecord>)->Unit,
) {
    private val handler=Handler(Looper.getMainLooper())
    private val registry=DeviceStateRegistry()
    private val scopes=MiLinkDeviceOwnershipRegistry()
    private val records=initial.take(BatteryWidgetProjection.MAX_DEVICES).associateByTo(linkedMapOf()){it.address}
    private var producer:IBinder?=null
    private var death:IBinder.DeathRecipient?=null
    private val retired=ArrayDeque<IBinder>()
    fun records():List<BatteryWidgetRecord> = records.values.toList()
    fun accept(intent:Intent):Boolean = runCatching {
        val state=with(ModuleContract){intent.readState()}?:return false
        val address=BatteryWidgetProjection.address(state.address)?:return false
        if(records.size>=BatteryWidgetProjection.MAX_DEVICES && address !in records)return false
        if(state.revision<0 || state.features.values.size>1 || state.features.values.any{it !is BatteryFeatureState})return false
        val token=with(ModuleContract){intent.readSessionToken()}?.takeIf{it.length in 1..128}?:return false
        val metadata=NativePresentationContract.metadata(intent)?:return false
        val nextProducer=NativePresentationContract.producer(intent)?.takeIf{it.isBinderAlive && it !in retired}?:return false
        val scope=MiLinkDeviceOwnershipRegistry.ConnectionScope(nextProducer,metadata.connection)
        if(!scopes.acceptsConnectionScope(address,scope))return false
        val recipient=if(nextProducer!=producer) IBinder.DeathRecipient {
            handler.post { if(producer==nextProducer)invalidateProducer() }
        } else null
        if(recipient!=null) {
            if(runCatching {nextProducer.linkToDeath(recipient,0)}.isFailure || !nextProducer.isBinderAlive) {
                runCatching {nextProducer.unlinkToDeath(recipient,0)}
                return false
            }
        }
        if(registry.accept(state,token)==null) {
            recipient?.let {runCatching {nextProducer.unlinkToDeath(it,0)}}
            return false
        }
        if(recipient!=null) {
            producer?.let{old->death?.let{runCatching{old.unlinkToDeath(it,0)}};retired.addLast(old)}
            while(retired.size>16)retired.removeFirst()
            producer=nextProducer;death=recipient
            records.replaceAll{_,r->r.copy(verified=false)}
        }
        scopes.bindConnectionScope(address,scope)
        if(metadata.admission==NativeAdmission.SYSTEM)scopes.claimSharedSystemOwnership(address,scope)
        val acceptedMetadata=if(scopes.isSystemOwned(address))metadata.copy(admission=NativeAdmission.SYSTEM)else metadata
        val timestamp=intent.getLongExtra(BatteryWidgetPublisher.RECORDED_AT,0).takeIf{it>0 && it<=System.currentTimeMillis()+60_000}
        val record=BatteryWidgetProjection.from(state,acceptedMetadata,timestamp,true)?:return false
        records[address]=record
        changed(records())
        true
    }.getOrDefault(false)
    private fun invalidateProducer() {
        producer?.let{retired.addLast(it)}
        while(retired.size>16)retired.removeFirst()
        producer=null;death=null
        records.replaceAll{_,r->r.copy(verified=false,status=if(r.status==BatteryWidgetStatus.SYSTEM)r.status else BatteryWidgetStatus.UNCONFIRMED)}
        changed(records())
    }
    fun close() {
        producer?.let{p->death?.let{runCatching{p.unlinkToDeath(it,0)}}}
        producer=null;death=null;registry.clear();scopes.clear();records.clear();retired.clear()
    }
}
