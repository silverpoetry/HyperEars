package dev.hyperears.hook
import android.view.View
import dev.hyperears.integration.*
import dev.hyperears.bridge.VendorHandoffLauncher

internal class SettingsHeadsetBinding(
    private val host:SettingsPreferenceHost,private val anchor:View,private val address:String,
    private val currentAddress:()->String?,private val record:()->SettingsHeadsetState.Record?,
    private val environment:MiLinkCardEnvironment,
    private val canReturn:(SettingsHeadsetState.Record)->Boolean={false},
    private val nativeApple: Boolean = false,
) {
    private var closed=false
    private var token:String?=null
    private var category:Any?=null
    private var details:MiLinkToggleDetails?=null
    private var confirmation:android.content.DialogInterface?=null
    private fun current()=record()?.takeIf {currentAddress().equals(address,true)&&it.state.sessionActive && it.state.adapter?.id?.startsWith("apple-")==true}
    private fun writable(state:EarbudState)=state.privateChannelConnected&&state.handshakeAccepted&&state.lifecycle.controlOwnership==ControlOwnership.MODULE
    fun render() {
        if(closed)return
        val value=current() ?: run {
            details?.dismiss();details=null;confirmation?.dismiss();confirmation=null
            category?.let(host::detach);category=null
            return
        }
        if(token!=value.token) {details?.dismiss();details=null;confirmation?.dismiss();confirmation=null;token=value.token}
        val state=value.state
        if(!writable(state)) {details?.dismiss();details=null;confirmation?.dismiss();confirmation=null}
        details?.render(state)
        host.beginUpdate()
        try {
            val group=host.category("hyperears.headset","耳机设置");category=group;host.add(host.screen,group)
            val add:(Any)->Unit={host.add(group,it)}
            val latest:()->EarbudState?={current()?.takeIf {it.token==value.token}?.state}
            val send:(ControlRequest)->Unit={request->
                val fresh=latest() ?: error("Device session changed");check(writable(fresh));environment.controlSender(address,request)
            }
            val listening = state.features.get<AppleListeningModeFeatureState>()
            if (listening != null && listening.supportedModes.isNotEmpty() &&
                (!nativeApple || AppleListeningMode.ADAPTIVE in listening.supportedModes)) {
                val modes = listening.supportedModes.toList()
                val labels = modes.map { when (it) {
                    AppleListeningMode.OFF -> "关闭"
                    AppleListeningMode.ANC -> "降噪"
                    AppleListeningMode.TRANSPARENCY -> "通透"
                    AppleListeningMode.ADAPTIVE -> "自适应"
                } }
                add(host.choice("hyperears.apple.listening", if (nativeApple) "聆听模式（含自适应）" else "聆听模式", labels,
                    modes.map { it.name }, listening.mode?.name, AppleListeningCardPolicy.render(state).enabled) { selected ->
                    val fresh = latest() ?: return@choice
                    AppleListeningMode.entries.find { it.name == selected }?.let { mode ->
                        AppleListeningCardPolicy.request(fresh, mode)?.let(send)
                    }
                })
            }
            SettingsAppleRows.add(host, state, address, add, latest, send)
            if (AppleSettingsCardPolicy.available(state, address)) {
                val advanced = host.row("hyperears.apple.advanced", "高级设置")
                host.enable(advanced, writable(state))
                host.onClick(advanced) {
                    val fresh = latest() ?: return@onClick
                    if (!closed && writable(fresh) && AppleSettingsCardPolicy.available(fresh, address)) {
                        details?.dismiss(); details = AppleSettingsDetails.open(anchor, address, environment)
                    }
                }
                add(advanced)
            }
            val returning=state.lifecycle.controlOwnership==ControlOwnership.EXTERNAL_APP
            val row=host.row("hyperears.controller",if(returning)"返回 HyperEars 控制" else "打开 AndPods")
            host.enable(row,if(returning)canReturn(value) else writable(state))
            host.onClick(row) {
                val fresh=current()?.takeIf {it.token==value.token} ?: return@onClick
                val ok=if(returning&&canReturn(fresh))VendorHandoffLauncher.returnControl(address,environment.sessionTokenProvider,environment.controlSender)
                    else if(!returning&&writable(fresh.state))VendorHandoffLauncher.open(anchor.context,address,
                        fresh.state.adapter?.controlApps.orEmpty(),environment.stateProvider,environment.sessionTokenProvider,
                        environment.observeState,environment.controlSender) else false
                if(!ok)host.summary(row,"打开失败，请检查应用安装和设备连接")
            }
            add(row)
        } catch(error:Exception) {ModuleLog.warn("Settings","Apple controls failed",error);close()}
        finally {host.endUpdate()}
    }
    fun reconcile()=render()
    fun close() {if(closed)return;closed=true;details?.dismiss();confirmation?.dismiss();category?.let(host::detach);host.dispose()}
}
