package dev.hyperears.ui.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.hyperears.bridge.BroadcastIdentity
import dev.hyperears.bridge.ModuleContract
import dev.hyperears.bridge.NativePresentationContract
import java.io.Closeable
import java.util.UUID

/** Both renderers use the same authenticated, one-shot debug request. No display retries. */
@Composable
internal fun rememberNativePreviewAction(): (String) -> Unit {
    val context = LocalContext.current.applicationContext
    val action = remember(context) { NativePreviewAction(context) }
    DisposableEffect(action) {
        action.start()
        onDispose { action.close() }
    }
    return action::request
}

private class NativePreviewAction(private val context: Context) : Closeable {
    private val handler = Handler(Looper.getMainLooper())
    private var pendingId: String? = null
    private val timeout = Runnable {
        pendingId = null
        show("未收到系统耳机服务响应，请重启蓝牙后重试")
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!BroadcastIdentity.accepts(context, sentFromPackage, sentFromUid,
                    ModuleContract.NATIVE_HEADSET_PACKAGE)) return
            val id = NativePresentationContract.previewId(intent) ?: return
            if (id != pendingId) return
            val result = NativePresentationContract.previewResult(intent) ?: return
            handler.removeCallbacks(timeout)
            pendingId = null
            show(result.message)
        }
    }

    fun start() {
        context.registerReceiver(receiver, IntentFilter(NativePresentationContract.ACTION_PREVIEW_RESULT),
            Context.RECEIVER_EXPORTED)
    }

    fun request(kind: String) {
        require(kind == "hint" || kind == "popup")
        handler.removeCallbacks(timeout)
        val id = UUID.randomUUID().toString()
        pendingId = id
        // This timeout reports a missing IPC response; it never reopens a window or re-sends.
        handler.postDelayed(timeout, 3_000)
        runCatching { BroadcastIdentity.send(context, NativePresentationContract.preview(kind, id)) }
            .onFailure {
                handler.removeCallbacks(timeout)
                pendingId = null
                show("无法发送预览请求")
            }
    }

    override fun close() {
        handler.removeCallbacks(timeout)
        pendingId = null
        context.unregisterReceiver(receiver)
    }

    private fun show(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
