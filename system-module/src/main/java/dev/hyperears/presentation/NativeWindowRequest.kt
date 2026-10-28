package dev.hyperears.presentation

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import dev.hyperears.bridge.BroadcastIdentity
import dev.hyperears.bridge.ModuleContract
import dev.hyperears.bridge.NativePresentationContract
import dev.hyperears.integration.EarbudState
import java.util.UUID

internal data class NativeWindowRequest(
    val id: String,
    val state: EarbudState,
    val session: String,
    val metadata: NativePresentationMetadata,
    val producer: IBinder,
) {
    fun forBinding(
        latest: NativeWindowRequest?,
        currentProducer: IBinder?,
        hasAcceptedState: Boolean,
    ): NativeWindowRequest? {
        if (!state.sessionActive || metadata.admission != NativeAdmission.HYPEREARS ||
            (currentProducer != null && currentProducer != producer)
        ) {
            return null
        }
        // A known lifecycle without an eligible record has ended or ceded native ownership.
        if (latest == null) return takeUnless { hasAcceptedState }
        return updated(latest.state, latest.session, latest.metadata, latest.producer)
    }

    fun updated(
        state: EarbudState,
        session: String,
        metadata: NativePresentationMetadata,
        producer: IBinder,
    ): NativeWindowRequest? {
        if (state.address != this.state.address || producer != this.producer || session != this.session ||
            metadata.connection != this.metadata.connection ||
            metadata.admission != NativeAdmission.HYPEREARS || !state.sessionActive
        ) {
            return null
        }
        return if (state.revision > this.state.revision) copy(state = state, metadata = metadata) else this
    }

    fun stateArray(): Array<String> {
        val p = requireNotNull(NativeHeadsetProjection.from(state))
        return arrayOf(
            p.left.percent?.toString() ?: "-",
            p.right.percent?.toString() ?: "-",
            p.chargingCase.percent?.toString() ?: "-",
            "false",
            "false",
            p.left.charging.toString(),
            p.right.charging.toString(),
            p.chargingCase.charging.toString(),
            p.address,
            "2",
            p.name,
        )
    }

    companion object {
        const val ACTIVITY = "com.android.bluetooth.ble.app.fastconnect.MiuiFastConnectActivity"
        private const val PAYLOAD = "dev.hyperears.native_snapshot"
        private const val DEADLINE = "dev.hyperears.native_deadline"

        fun launch(context: Context, request: NativeWindowRequest, device: android.bluetooth.BluetoothDevice) {
            val projection = requireNotNull(NativeHeadsetProjection.from(request.state))
            val payload = NativePresentationContract.attach(
                ModuleContract.stateChanged(request.state, request.session, ModuleContract.NATIVE_HEADSET_PACKAGE),
                request.metadata, request.producer,
            )
            val intent = Intent("com.android.bluetooth.FAST_CONNECT_DEVICE")
                .setClassName(ModuleContract.NATIVE_HEADSET_PACKAGE, ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(NativePresentationContract.EXTRA_WINDOW, request.id)
                .putExtra(PAYLOAD, payload)
                .putExtra(DEADLINE, SystemClock.elapsedRealtime() + 5_000)
                .putExtra("android.bluetooth.device.extra.DEVICE", device)
                .putExtra("headset_addresses", projection.address)
                .putExtra("device_name_over_write", projection.name)
                .putExtra("headset_extra_data", intArrayOf(0, 0, 0, 16, 0, if (projection.singleBattery) 3 else 0))
            context.startActivity(intent, ActivityOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
        }

        fun read(activity: Activity): NativeWindowRequest? {
            val caller = activity.initialCaller
            if (!BroadcastIdentity.accepts(activity, caller.`package`, caller.uid,
                    ModuleContract.NATIVE_HEADSET_PACKAGE)) return null
            val intent = activity.intent
            if (SystemClock.elapsedRealtime() > intent.getLongExtra(DEADLINE, -1)) return null
            val id = intent.getStringExtra(NativePresentationContract.EXTRA_WINDOW)
                ?.takeIf { runCatching { UUID.fromString(it) }.isSuccess } ?: return null
            val payload = intent.getParcelableExtra(PAYLOAD, Intent::class.java) ?: return null
            val state = with(ModuleContract) { payload.readState() } ?: return null
            val session = with(ModuleContract) { payload.readSessionToken() } ?: return null
            val metadata = NativePresentationContract.metadata(payload) ?: return null
            val producer = NativePresentationContract.producer(payload)?.takeIf { it.isBinderAlive } ?: return null
            if (!state.sessionActive || metadata.admission != NativeAdmission.HYPEREARS ||
                state.address != intent.getStringExtra("headset_addresses")) return null
            return NativeWindowRequest(id, state, session, metadata, producer)
        }
    }
}
