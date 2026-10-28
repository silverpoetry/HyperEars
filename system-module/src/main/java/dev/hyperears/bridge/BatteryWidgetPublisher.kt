package dev.hyperears.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import dev.hyperears.integration.*
import dev.hyperears.presentation.NativePresentationMetadata
import dev.hyperears.widget.BatteryWidgetProjector
import dev.hyperears.widget.WidgetStateReceiver

/** Explicit, battery-only consumer of the existing authenticated state contract. */
object BatteryWidgetPublisher {
    internal const val RECORDED_AT="widget_battery_recorded_at"
    private val projector=BatteryWidgetProjector()
    fun publish(context:Context,state:EarbudState,sessionToken:String,presentation:NativePresentationMetadata?,producer:IBinder?,replay:Boolean=false) {
        if(presentation==null || producer?.isBinderAlive!=true || sessionToken.isBlank())return
        runCatching {
            val record=projector.accept(state,sessionToken,presentation,producer,replay,System.currentTimeMillis())?:return
            val adapter=state.adapter?.copy(capabilities=EarbudCapabilities(battery=record.hasBattery),supportedNoiseModes=emptySet(),
                presentationId=null,transportKinds=emptySet(),controlApps=emptyList(),artwork=HeadsetArtwork.defaultFor(record.formFactor))
            val reduced=state.copy(adapter=adapter,deviceName=record.name,address=record.address,
                features=DeviceFeatureSnapshot(listOf(BatteryFeatureState(record.battery))))
            val intent=ModuleContract.stateChanged(reduced,sessionToken,ModuleContract.MODULE_PACKAGE)
                .setComponent(ComponentName(ModuleContract.MODULE_PACKAGE,WidgetStateReceiver::class.java.name))
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            record.recordedAtMillis?.let{intent.putExtra(RECORDED_AT,it)}
            NativePresentationContract.attach(intent,presentation.copy(replay=replay),producer)
            BroadcastIdentity.send(context,intent)
        }
    }
}
