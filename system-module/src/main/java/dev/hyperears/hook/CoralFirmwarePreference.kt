package dev.hyperears.hook

import android.os.Handler
import android.os.Looper
import dev.hyperears.firmware.CoralFirmwareCheckFlow
import dev.hyperears.firmware.CoralFirmwareRepository
import dev.hyperears.integration.RoseCoralInfoFeatureState
import java.util.concurrent.Executors

/** One metadata request per page/session/version, never downloads or installs firmware. */
internal class CoralFirmwarePreference(
    private val current: () -> SettingsHeadsetState.Record?,
    private val refresh: () -> Unit,
    private val openRoseLink: () -> Boolean,
) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "HyperEars-CoralFirmware").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private var flow: CoralFirmwareCheckFlow? = null
    private var key: Pair<String, Int>? = null
    private var summary = "等待读取耳机固件版本"
    private var update = false
    private var retry = false
    private var closed = false

    fun add(host: SettingsPreferenceHost, value: SettingsHeadsetState.Record, add: (Any) -> Unit) {
        val version = value.state.features.get<RoseCoralInfoFeatureState>()?.firmwareVersion?.toIntOrNull()?.takeIf { it in 1..65535 }
        if (version != null && key != (value.token to version)) {
            flow?.close()
            key = value.token to version
            summary = "正在检查更新…"; update = false; retry = false
            val captured = key
            val check = CoralFirmwareCheckFlow({ worker.execute(it) }, { task -> main.post { task() } }, CoralFirmwareRepository()::check)
            flow = check
            check.start(version, "zh", {
                !closed && key == captured && current()?.let {
                    it.token == value.token && it.state.features.get<RoseCoralInfoFeatureState>()?.firmwareVersion?.toIntOrNull() == version
                } == true
            }) { result ->
                result.onSuccess { info ->
                    update = info.updateAvailable
                    summary = if (update) "发现新版本，点击打开 RoseLink 更新" else "已是最新版本"
                }.onFailure { summary = "检查失败，点击重试"; retry = true }
                refresh()
            }
        }
        val row = host.row("hyperears.coral.firmware", "固件更新")
        host.summary(row, if (version == null) summary else "当前版本 $version · $summary")
        host.enable(row, update || retry)
        host.onClick(row) {
            if (update) {
                if (!openRoseLink()) { summary = "打开 RoseLink 失败，请确认已安装"; refresh() }
            } else if (retry) { key = null; refresh() }
        }
        add(row)
    }

    override fun close() { closed = true; flow?.close(); worker.shutdownNow() }
}
