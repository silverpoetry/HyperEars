package dev.hyperears.bridge

import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import dev.hyperears.presentation.HeadsetConnection
import dev.hyperears.presentation.NativeAdmission
import dev.hyperears.presentation.NativePresentationMetadata
import dev.hyperears.presentation.NativePresentationResult
import java.util.UUID

/** Transport metadata for the existing accepted state, never an alternative battery/control bus. */
internal object NativePresentationContract {
    const val ACTION_ADMISSION = "dev.hyperears.action.NATIVE_ADMISSION"
    const val ACTION_PREVIEW = "dev.hyperears.action.NATIVE_PREVIEW"
    const val ACTION_PREVIEW_RESULT = "dev.hyperears.action.NATIVE_PREVIEW_RESULT"
    const val ACTION_WINDOW = "dev.hyperears.action.NATIVE_WINDOW"
    const val EXTRA_WINDOW = "dev.hyperears.native_window"
    private const val CONNECTION = "native_connection"
    private const val CONNECTED_AT = "native_connected_at"
    private const val ADMISSION = "native_admission"
    private const val REPLAY = "native_replay"
    private const val PRODUCER = "native_producer"
    private const val ADDRESS = "native_address"
    private const val SESSION = "native_session"
    private const val KIND = "native_kind"
    private const val PREVIEW_ID = "native_preview_id"
    private const val PREVIEW_RESULT = "native_preview_result"

    fun attach(intent: Intent, metadata: NativePresentationMetadata, producer: IBinder): Intent =
        intent.putExtra(CONNECTION, metadata.connection.id)
            .putExtra(CONNECTED_AT, metadata.connection.connectedAtElapsedMs)
            .putExtra(ADMISSION, metadata.admission.name)
            .putExtra(REPLAY, metadata.replay)
            .putExtras(Bundle().apply { putBinder(PRODUCER, producer) })

    fun metadata(intent: Intent): NativePresentationMetadata? {
        val id = intent.getStringExtra(CONNECTION)?.takeIf { it.length in 1..80 } ?: return null
        if (!intent.hasExtra(CONNECTED_AT)) return null
        val admission = intent.getStringExtra(ADMISSION)
            ?.let { runCatching { NativeAdmission.valueOf(it) }.getOrNull() } ?: return null
        return NativePresentationMetadata(
            HeadsetConnection(id, intent.getLongExtra(CONNECTED_AT, -1)),
            admission,
            intent.getBooleanExtra(REPLAY, false),
        )
    }

    fun producer(intent: Intent): IBinder? = intent.extras?.getBinder(PRODUCER)

    data class AdmissionReport(val address: String, val session: String, val admission: NativeAdmission)

    fun admission(address: String, token: String, admission: NativeAdmission): Intent = Intent(ACTION_ADMISSION)
        .setPackage(ModuleContract.BLUETOOTH_PACKAGE)
        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        .putExtra(ADDRESS, address).putExtra(SESSION, token)
        .putExtra(ADMISSION, admission.name)

    fun readAdmission(intent: Intent): AdmissionReport? {
        val address = intent.getStringExtra(ADDRESS)?.takeIf(String::isNotBlank) ?: return null
        val session = intent.getStringExtra(SESSION)?.takeIf(String::isNotBlank) ?: return null
        val admission = intent.getStringExtra(ADMISSION)
            ?.let { runCatching { NativeAdmission.valueOf(it) }.getOrNull() }
            ?.takeIf { it != NativeAdmission.UNKNOWN } ?: return null
        return AdmissionReport(address, session, admission)
    }

    fun preview(kind: String, id: String): Intent = Intent(ACTION_PREVIEW)
        .setPackage(ModuleContract.NATIVE_HEADSET_PACKAGE).putExtra(KIND, kind).putExtra(PREVIEW_ID, id)

    fun previewId(intent: Intent): String? = intent.getStringExtra(PREVIEW_ID)
        ?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }

    fun previewResult(id: String, result: NativePresentationResult): Intent = Intent(ACTION_PREVIEW_RESULT)
        .setPackage(ModuleContract.MODULE_PACKAGE).putExtra(PREVIEW_ID, id).putExtra(PREVIEW_RESULT, result.name)

    fun previewResult(intent: Intent): NativePresentationResult? = intent.getStringExtra(PREVIEW_RESULT)
        ?.let { runCatching { NativePresentationResult.valueOf(it) }.getOrNull() }

    fun kind(intent: Intent): String? = intent.getStringExtra(KIND)
}
