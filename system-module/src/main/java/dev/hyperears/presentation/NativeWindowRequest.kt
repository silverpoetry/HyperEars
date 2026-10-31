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
import dev.hyperears.hook.ModuleLog
import java.util.UUID

internal data class NativeWindowRequest(
    val id: String,
    val state: EarbudState,
    val session: String,
    val metadata: NativePresentationMetadata,
    val producer: IBinder,
    val pairedDiscovery: Boolean = false,
) {
    fun forBinding(
        latest: NativeWindowRequest?,
        currentProducer: IBinder?,
        hasAcceptedState: Boolean = latest != null,
    ): NativeWindowRequest? {
        if ((!state.sessionActive && !pairedDiscovery) || metadata.admission != NativeAdmission.HYPEREARS ||
            (currentProducer != null && currentProducer != producer)
        ) {
            return null
        }
        if (pairedDiscovery) return takeIf {
            !state.sessionActive && producer.isBinderAlive && (latest == null ||
                (!latest.state.sessionActive && latest.producer == producer && latest.session == session &&
                    latest.metadata.connection == metadata.connection && latest.metadata.admission == NativeAdmission.HYPEREARS))
        }
        // A known lifecycle without an eligible record has ended or ceded native ownership.
        if (latest == null) return takeUnless { hasAcceptedState }
        if (latest.producer != producer || latest.state.address != state.address) return null
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

    fun projection(): NativeHeadsetProjection? = if (pairedDiscovery)
        NativeHeadsetProjection.fromPairedDiscovery(state) else NativeHeadsetProjection.from(state)

    fun stateArray(): Array<String> {
        val p = requireNotNull(projection())
        val wear = nativeAppleWearFlags(state)
        return arrayOf(
            p.left.percent?.toString() ?: "-",
            p.right.percent?.toString() ?: "-",
            p.chargingCase.percent?.toString() ?: "-",
            wear.first,
            wear.second,
            p.left.charging.toString(),
            p.right.charging.toString(),
            p.chargingCase.charging.toString(),
            p.address,
            if (state.lifecycle.systemProfile == dev.hyperears.integration.SystemProfileState.CONNECTING) "1" else "2",
            p.name,
        )
    }

    companion object {
        const val ACTIVITY = "com.android.bluetooth.ble.app.fastconnect.MiuiFastConnectActivity"
        private const val PAYLOAD = "dev.hyperears.native_snapshot"
        private const val DEADLINE = "dev.hyperears.native_deadline"

        fun launch(context: Context, request: NativeWindowRequest, device: android.bluetooth.BluetoothDevice) {
            val projection = requireNotNull(request.projection())
            val intent = Intent("com.android.bluetooth.FAST_CONNECT_DEVICE_AIRPODS")
                .setClassName(ModuleContract.NATIVE_HEADSET_PACKAGE, ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra("android.bluetooth.device.extra.DEVICE", device)
                .putExtra("headset_addresses", projection.address)
                .putExtra("device_name_over_write", projection.name)
                .putExtra("headset_extra_data", intArrayOf(-60, 1, if (usesNativeAppleResources(request.state)) 1 else 0, 16, 0, projection.nativeWearType))
            launchIntent(context, request, intent)
        }

        /** Keep the native helper's action, flags and rendering extras; attach only our state provenance. */
        fun launchIntent(context: Context, request: NativeWindowRequest, intent: Intent) {
            val payload = NativePresentationContract.attach(
                ModuleContract.stateChanged(request.state, request.session, ModuleContract.NATIVE_HEADSET_PACKAGE),
                request.metadata, request.producer,
            )
            intent.putExtra(NativePresentationContract.EXTRA_WINDOW, request.id)
                .putExtra(PAYLOAD, payload)
                .putExtra("dev.hyperears.paired_discovery", request.pairedDiscovery)
                .putExtra(DEADLINE, SystemClock.elapsedRealtime() + 5_000)
            context.startActivity(intent, ActivityOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
        }

        fun read(activity: Activity): NativeWindowRequest? {
            fun reject(reason: String): NativeWindowRequest? {
                ModuleLog.warn("NativeWindow", "window request rejected: $reason")
                return null
            }
            val caller = activity.initialCaller
            if (!BroadcastIdentity.accepts(activity, caller.`package`, caller.uid,
                    ModuleContract.NATIVE_HEADSET_PACKAGE)) return reject("caller identity")
            val intent = activity.intent
            val deadline = intent.getLongExtra(DEADLINE, -1)
            val now = SystemClock.elapsedRealtime()
            if (deadline < 0) return reject("launch deadline missing")
            if (now > deadline) return reject("launch deadline elapsed by ${now - deadline}ms; request=${intent.getStringExtra(NativePresentationContract.EXTRA_WINDOW)}")
            val id = intent.getStringExtra(NativePresentationContract.EXTRA_WINDOW)
                ?.takeIf { runCatching { UUID.fromString(it) }.isSuccess } ?: return reject("request id")
            val payload = intent.getParcelableExtra(PAYLOAD, Intent::class.java) ?: return reject("snapshot payload missing")
            val state = with(ModuleContract) { payload.readState() } ?: return reject("snapshot decode")
            val session = with(ModuleContract) { payload.readSessionToken() } ?: return reject("session missing")
            val metadata = NativePresentationContract.metadata(payload) ?: return reject("metadata missing")
            val producer = NativePresentationContract.producer(payload)?.takeIf { it.isBinderAlive } ?: return reject("producer unavailable")
            val discovery = intent.getBooleanExtra("dev.hyperears.paired_discovery", false)
            if ((discovery && state.sessionActive) || (!discovery && !state.sessionActive) || metadata.admission != NativeAdmission.HYPEREARS ||
                state.address != intent.getStringExtra("headset_addresses")) return reject("connection admission or target mismatch")
            return NativeWindowRequest(id, state, session, metadata, producer, discovery)
        }
    }
}

/** Apple display resources stay stable while device-information telemetry is still arriving. */
internal fun usesNativeAppleResources(state: EarbudState): Boolean =
    state.adapter?.id in setOf(
        dev.hyperears.integration.AppleAirPodsAdapter.ID,
        dev.hyperears.integration.AppleAirPodsProAdapter.ID,
        dev.hyperears.integration.AppleAirPodsMaxAdapter.ID,
    )
