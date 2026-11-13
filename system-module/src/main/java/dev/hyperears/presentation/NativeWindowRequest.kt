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
    val kind: NativeWindowKind = NativeWindowKind.AIRPODS,
) {
    fun forBinding(
        latest: NativeWindowRequest?,
        currentProducer: IBinder?,
    ): NativeWindowRequest? {
        if (!state.sessionActive || metadata.admission != NativeAdmission.HYPEREARS ||
            (currentProducer != null && currentProducer != producer)
        ) {
            return null
        }
        if (latest == null) return this
        if (latest.producer != producer || latest.state.address != state.address || latest.kind != kind) return null
        // Native ownership is sticky for this physical connection, including an older snapshot.
        if (latest.metadata.connection == metadata.connection &&
            latest.metadata.admission == NativeAdmission.SYSTEM) return null
        // Revisions are monotonic per address within a producer, including session replacement.
        // The Activity can reach this process before its newer broadcast does.
        if (latest.state.revision < state.revision) return this
        return updated(latest.state, latest.session, latest.metadata, latest.producer)
    }

    fun updated(
        state: EarbudState,
        session: String,
        metadata: NativePresentationMetadata,
        producer: IBinder,
    ): NativeWindowRequest? {
        if (state.address != this.state.address || producer != this.producer || session != this.session ||
            metadata.connection != this.metadata.connection || metadata.admission == NativeAdmission.SYSTEM
        ) {
            return null
        }
        if (state.revision < this.state.revision) return this
        if (metadata.admission != NativeAdmission.HYPEREARS || !state.sessionActive) return null
        return if (state.revision > this.state.revision) copy(state = state, metadata = metadata) else this
    }

    companion object {
        const val ACTIVITY = "com.android.bluetooth.ble.app.fastconnect.MiuiFastConnectActivity"
        private const val PAYLOAD = "dev.hyperears.native_snapshot"
        private const val DEADLINE = "dev.hyperears.native_deadline"
        private const val ADDRESS = "dev.hyperears.native_address"
        private const val KIND = "dev.hyperears.native_window_kind"

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
                .putExtra(ADDRESS, projection.address)
                .putExtra(KIND, request.kind.name)
                .putExtra("android.bluetooth.device.extra.DEVICE", device)
                .putExtra("headset_addresses", projection.address)
                .putExtra("device_name_over_write", projection.name)
                .putExtra("headset_extra_data", intArrayOf(0, 0, 0, 16, 0, if (projection.singleBattery) 3 else 0))
            if (request.kind == NativeWindowKind.LEGACY_XIAOMI) {
                intent.putExtra("headset_addresses", arrayOf(projection.address, "", projection.name))
                // No real advertisement or Xiaomi product identity is supplied to this renderer.
                intent.putExtra("headset_extra_data", intArrayOf(0, 0, 0, 0, 0, 0))
            }
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
            val kind = intent.getStringExtra(KIND)?.let { value -> NativeWindowKind.entries.find { it.name == value } }
                ?: if (!intent.hasExtra(KIND)) NativeWindowKind.AIRPODS else return null
            if (!state.sessionActive || metadata.admission != NativeAdmission.HYPEREARS ||
                state.address != (intent.getStringExtra(ADDRESS) ?: intent.getStringExtra("headset_addresses"))) return null
            return NativeWindowRequest(id, state, session, metadata, producer, kind)
        }
    }
}
