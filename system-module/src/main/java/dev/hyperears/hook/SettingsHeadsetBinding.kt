package dev.hyperears.hook

import android.app.Dialog
import android.content.DialogInterface
import android.os.SystemClock
import android.view.View
import android.widget.Toast
import dev.hyperears.bridge.VendorHandoffLauncher
import dev.hyperears.integration.*

internal class SettingsHeadsetBinding(
    private val host: SettingsPreferenceHost,
    private val anchor: View,
    private val address: String,
    private val currentAddress: () -> String?,
    private val record: () -> SettingsHeadsetState.Record?,
    private val environment: MiLinkCardEnvironment,
    private val canReturn: (SettingsHeadsetState.Record) -> Boolean = { false },
) {
    private var closed = false
    private var token: String? = null
    private var category: Any? = null
    private var confirmation: DialogInterface? = null
    private var findDialog: Dialog? = null
    private var firmware: CoralFirmwarePreference? = null
    private val pending = CoralSliderPendingState<RoseCoralSetting>()
    private fun current() = record()?.takeIf {
        currentAddress().equals(address, true) && CoralNativePageModel.eligible(it.state)
    }
    private fun writable(state: EarbudState) = RoseCoralCardPolicy.ready(state, address)
    private fun failure() = Toast.makeText(anchor.context, "修改失败", Toast.LENGTH_SHORT).show()
    private fun clearTransient() {
        pending.reset()
        confirmation?.dismiss(); confirmation = null
        findDialog?.dismiss(); findDialog = null
        firmware?.close(); firmware = null
    }
    fun render() {
        if (closed) return
        val accepted = current() ?: run {
            clearTransient(); category?.let(host::detach); category = null
            return
        }
        if (token != accepted.token) { clearTransient(); token = accepted.token }
        val state = accepted.state
        if (!writable(state)) {
            pending.reset(); confirmation?.dismiss(); confirmation = null
            findDialog?.dismiss(); findDialog = null
        }
        host.beginUpdate()
        try {
            val group = host.category("hyperears.headset", "耳机设置")
            category = group; host.add(host.screen, group)
            val add: (Any) -> Unit = { host.add(group, it) }
            val latest: () -> EarbudState? = { current()?.takeIf { it.token == accepted.token }?.state }
            val send: (ControlRequest) -> Unit = {
                check(!closed && latest()?.let(::writable) == true)
                environment.controlSender(address, it)
            }
            val confirm: (String, () -> Unit) -> Unit = { message, action ->
                confirmation?.dismiss()
                confirmation = host.confirm(message, { confirmation = null }) {
                    if (!closed && latest()?.let(::writable) == true) action()
                }
            }
            SettingsCoralRows.add(host, state, address, add, latest, send, confirm,
                expanded = true,
                sliderProjection = { setting, reported ->
                    val projected = pending.project(setting, reported, accepted.token, SystemClock.elapsedRealtime())
                    if (projected.timedOut != null) failure()
                    projected.value to projected.enabled
                },
                submitSlider = { setting, old, target, request ->
                    val lease = pending.submit(setting, old, target, accepted.token, SystemClock.elapsedRealtime())
                    if (lease == null) false else runCatching {
                        send(request)
                        anchor.postDelayed({
                            if (!closed && pending.expire(lease, token.orEmpty(), SystemClock.elapsedRealtime()) != null) {
                                failure(); render()
                            }
                        }, 2500)
                        true
                    }.getOrElse { pending.cancel(lease, accepted.token); failure(); false }
                },
                openFind = { labels, summary, selected ->
                    findDialog?.dismiss()
                    host.persistentChoices("查找耳机", summary, labels, selected).also { dialog ->
                        findDialog = dialog
                        dialog.setOnDismissListener { if (findDialog === dialog) findDialog = null }
                    }
                })
            state.features.get<RoseCoralFindFeatureState>()?.let {
                fun text(value: Boolean?) = when(value) { true -> "响铃中"; false -> "已停止"; null -> "未知" }
                findDialog?.let { dialog -> dialog.javaClass.getMethod("setMessage", CharSequence::class.java)
                    .invoke(dialog, "左耳：${text(it.left)} · 右耳：${text(it.right)}") }
            }
            if (state.lifecycle.controlOwnership == ControlOwnership.EXTERNAL_APP) {
                val row = host.row("hyperears.controller.return", "返回 HyperEars 控制")
                host.enable(row, canReturn(accepted))
                host.onClick(row) {
                    val fresh = current()?.takeIf { it.token == accepted.token } ?: return@onClick
                    if (canReturn(fresh)) VendorHandoffLauncher.returnControl(address, environment.sessionTokenProvider, environment.controlSender)
                }
                add(row)
            }
            val checker = firmware ?: CoralFirmwarePreference(::current, ::render) {
                val fresh = current()?.takeIf { it.token == accepted.token } ?: return@CoralFirmwarePreference false
                if (!writable(fresh.state)) false else VendorHandoffLauncher.open(anchor.context, address,
                    fresh.state.adapter?.controlApps.orEmpty(), environment.stateProvider, environment.sessionTokenProvider,
                    environment.observeState, environment.controlSender)
            }.also { firmware = it }
            checker.add(host, accepted, add)
        } catch (error: Exception) {
            ModuleLog.warn("Settings", "Coral native binding failed", error); close()
        } finally { host.endUpdate() }
    }
    fun reconcile() = render()
    fun close() { if (closed) return; closed = true; clearTransient(); category?.let(host::detach); host.dispose() }
}
