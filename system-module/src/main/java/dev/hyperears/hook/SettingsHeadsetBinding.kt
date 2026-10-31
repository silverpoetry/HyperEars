package dev.hyperears.hook
import android.view.View
import dev.hyperears.integration.*
import dev.hyperears.bridge.VendorHandoffLauncher

internal class SettingsHeadsetBinding(
    private val host:SettingsPreferenceHost,private val anchor:View,private val address:String,
    private val currentAddress:()->String?,private val record:()->SettingsHeadsetState.Record?,
    private val environment:MiLinkCardEnvironment,
    private val canReturn:(SettingsHeadsetState.Record)->Boolean={false},
) {
    private var closed=false
    private var token:String?=null
    private var category:Any?=null
    private var details:MiLinkToggleDetails?=null
    private var confirmation:android.content.DialogInterface?=null
    private fun current()=record()?.takeIf {currentAddress().equals(address,true)&&NativeHeadsetPageModel.eligible(it.state)}
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
            val confirm: (String, () -> Unit) -> Unit = { message, action ->
                confirmation?.dismiss()
                confirmation = host.confirm(message, { confirmation = null }) {
                    if (!closed && current()?.token == value.token && latest()?.let(::writable) == true) action()
                }
            }
            SettingsSonyInformationRows.add(host, state, false, add)
            SettingsSonyLeAudioRows.add(host, state, address, add, latest, send,
                openBluetooth = { anchor.context.startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) },
                confirm = confirm)
            SettingsSonyMultipointRows.add(host, state, address, add, latest, send)
            SettingsSonyAdaptiveRows.add(host, state, address, add, latest, send)
            SettingsSonyDevicesRows.add(host, state, address, add, latest, send, confirm)
            SettingsSonyQuickAccessRows.add(host, state, address, add, latest, send)
            SettingsSonyAudioRows.add(host, state, address, add, latest, send)
            SettingsSonyAssignableRows.add(host, state, address, add, latest, send)
            SettingsSonyVoiceRows.add(host, state, address, add, latest, send)
            SettingsSonyVirtualRows.add(host, state, address, add, latest, send)
            SettingsSonyEqualizerRows.add(host, state, address, add, latest, send)
            if(state.features.get<SonyEqFeatureState>()?.writableBands==true) {
                val eq=host.row("hyperears.sony.eq","均衡器")
                host.enable(eq,writable(state))
                host.onClick(eq) {
                    if(current()?.token==value.token&&latest()?.let(::writable)==true) {
                        details?.dismiss();details=SonyNativeEqDetails.open(anchor,address,environment)
                    }
                }
                add(eq)
            }
            val returning=state.lifecycle.controlOwnership==ControlOwnership.EXTERNAL_APP
            val row=host.row("hyperears.controller",if(returning)"返回 HyperEars 控制" else "打开 Sony Sound Connect")
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
            SettingsSonyPowerRow.add(host,state,address,add,latest,send,confirm)
            SettingsSonyInformationRows.add(host,state,true,add)
        } catch(error:Exception) {ModuleLog.warn("Settings","Sony controls failed",error);close()}
        finally {host.endUpdate()}
    }
    fun reconcile()=render()
    fun close() {if(closed)return;closed=true;details?.dismiss();confirmation?.dismiss();category?.let(host::detach);host.dispose()}
}
