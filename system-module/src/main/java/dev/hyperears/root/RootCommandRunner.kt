package dev.hyperears.root

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

enum class RootAction(
    val title: String,
    val detail: String,
    val command: String,
    val verificationCommand: String,
    val verificationSuccess: (String) -> Boolean,
) {
    RESTART_MILINK(
        title = "重启 MiLink",
        detail = "停止 MiLink；再次打开融合设备中心时由系统启动。",
        command = "am force-stop com.milink.service",
        verificationCommand = "pidof com.milink.service || true",
        verificationSuccess = String::isBlank,
    ),
    RESTART_BLUETOOTH(
        title = "重启蓝牙",
        detail = "重新启动蓝牙，已连接设备会暂时断开。",
        command = "svc bluetooth disable; sleep 1; svc bluetooth enable",
        verificationCommand = "settings get global bluetooth_on",
        verificationSuccess = { it.trim() == "1" },
    ),
    STOP_VENDOR_APPS(
        title = "停止厂商应用",
        detail = "停止受支持的厂商应用，并恢复 HyperEars 控制。",
        command = "for p in " +
            "com.vivo.vivotws com.heytap.headset com.oplus.melody " +
            "com.coloros.oppopods com.bose.bosemusic com.bose.monet " +
            "com.edifier.edifierconnect cn.ikaile.ruoshui.client " +
            "cn.lightyeartech.android com.yuandao.nicehck com.sony.songpal.mdr " +
            "com.qcymall.googleearphonesetup com.huawei.smartaudio; " +
            "do am force-stop \"\$p\" >/dev/null 2>&1 || true; done",
        verificationCommand = "for p in " +
            "com.vivo.vivotws com.heytap.headset com.oplus.melody " +
            "com.coloros.oppopods com.bose.bosemusic com.bose.monet " +
            "com.edifier.edifierconnect cn.ikaile.ruoshui.client " +
            "cn.lightyeartech.android com.yuandao.nicehck com.sony.songpal.mdr " +
            "com.qcymall.googleearphonesetup com.huawei.smartaudio; " +
            "do pidof \"\$p\" && exit 1; done; exit 0",
        verificationSuccess = { it.isBlank() },
    ),
}

sealed interface RootActionState {
    data object Idle : RootActionState
    data class Running(val action: RootAction) : RootActionState
    data class Finished(val action: RootAction, val success: Boolean, val detail: String) : RootActionState
}

/** Small, bounded root-command boundary used only from the module's Settings screen. */
internal object RootCommandRunner {
    suspend fun isAvailable(): Boolean = RootShell.execute("id -u").let { result ->
        result.success && result.output.trim() == "0"
    }

    suspend fun run(action: RootAction): RootActionState.Finished {
        val result = RootShell.execute(action.command)
        if (!result.success) {
            return RootActionState.Finished(
                action = action,
                success = false,
                detail = result.describe("执行"),
            )
        }
        val verification = RootShell.execute(action.verificationCommand)
        val verified = verification.success && action.verificationSuccess(verification.output)
        return RootActionState.Finished(
            action = action,
            success = verified,
            detail = buildString {
                append(result.describe("执行"))
                append('\n')
                append(verification.describe("验证"))
            },
        )
    }
}

internal data class RootCommandResult(
    val exitCode: Int?,
    val output: String,
) {
    val success: Boolean
        get() = exitCode == 0

    fun describe(stage: String): String = buildString {
        append(stage)
        append(" exit=")
        append(exitCode?.toString() ?: "timeout")
        if (output.isNotBlank()) {
            append(" · ")
            append(output)
        }
    }
}

/** Shared, bounded root process boundary for settings actions and diagnostics export. */
internal object RootShell {
    suspend fun execute(
        command: String,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    ): RootCommandResult = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val outputReader = async(Dispatchers.IO) {
                process.inputStream.bufferedReader().use { it.readText().trim() }
            }
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                outputReader.cancel()
                return@runCatching RootCommandResult(null, "命令执行超时")
            }
            RootCommandResult(process.exitValue(), outputReader.await())
        }.getOrElse { error ->
            RootCommandResult(null, error.message.orEmpty())
        }
    }

    private const val DEFAULT_TIMEOUT_SECONDS = 12L
}
