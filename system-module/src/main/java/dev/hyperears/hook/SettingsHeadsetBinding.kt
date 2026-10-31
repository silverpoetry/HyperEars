package dev.hyperears.hook

import android.view.View
import android.os.SystemClock
import dev.hyperears.integration.*
import dev.hyperears.bridge.VendorHandoffLauncher

/** One native page and one authenticated device; controls never open a transport here. */
internal class SettingsHeadsetBinding(
    private val host:SettingsPreferenceHost,
    private val anchor:View,
    private val address:String,
    private val currentAddress:()->String?,
    private val record:()->SettingsHeadsetState.Record?,
    private val environment:MiLinkCardEnvironment,
    private val canReturn:(SettingsHeadsetState.Record)->Boolean={false},
) {
    private var closed=false
    private var token:String?=null
    private val categories=mutableSetOf<Any>()
    private val pending=CoralSliderPendingState<RayNeoLevelSetting>(4_000)
    private val launches=RayNeoLaunchRows(anchor.context,::render)
    private var passwordDialog:android.app.Dialog?=null
    private val timeouts=mutableSetOf<Runnable>()
    private fun current()=record()?.takeIf { currentAddress().equals(address,true) && NativeHeadsetPageModel.eligible(it.state) }
    private fun group(key:String,title:String,order:Int)=host.category(key,title).also {
        host.order(it,order);host.add(host.screen,it);categories.add(it)
    }
    private fun writable(state:EarbudState)=SettingsRayNeoRows.ready(state,address)
    fun render() {
        if(closed)return
        val accepted=current() ?: run {
            timeouts.forEach(anchor::removeCallbacks);timeouts.clear()
            pending.reset();launches.close();passwordDialog?.dismiss();passwordDialog=null
            categories.forEach {runCatching {host.detach(it)}};categories.clear()
            token=null
            return
        }
        if(token!=accepted.token) {
            timeouts.forEach(anchor::removeCallbacks);timeouts.clear()
            pending.reset(accepted.token);launches.close();token=accepted.token
        }
        host.beginUpdate()
        try {
            val state=accepted.state
            val settings=group("hyperears.headset","眼镜设置",1000)
            val appGroup=group("hyperears.rayneo.apps","启动应用",1001)
            val official=group("hyperears.rayneo.official","官方应用",1002)
            val latest:()->EarbudState?={current()?.takeIf {it.token==accepted.token}?.state}
            val send:(ControlRequest)->Unit={request->
                val fresh=current()?.takeIf {it.token==accepted.token} ?: error("Device session changed")
                check(writable(fresh.state));environment.controlSender(address,request)
            }
            SettingsRayNeoRows.add(host,state,address,{host.add(settings,it)},latest,send,
                countryCode=java.util.Locale.getDefault().country.takeIf {it.matches(Regex("[A-Z]{2}"))}.orEmpty(),
                password={title,submitted->
                    passwordDialog?.dismiss()
                    passwordDialog=host.wifiPassword(title) {value->
                        if(current()?.token==accepted.token)submitted(value)
                    }
                },
                levelProjection={setting,value->
                    val projected=pending.project(setting,value,accepted.token,SystemClock.elapsedRealtime())
                    if(projected.timedOut!=null)failed()
                    projected.value to projected.enabled
                },
                submitLevel={setting,old,target,request->
                    val lease=pending.submit(setting,old,target,accepted.token,SystemClock.elapsedRealtime())
                    if(lease==null) false else try {
                        send(request)
                        val task=object:Runnable {override fun run() {
                            timeouts.remove(this)
                            if(!closed&&pending.expire(lease,token.orEmpty(),SystemClock.elapsedRealtime())!=null) {failed();render()}
                        }}
                        timeouts.add(task);anchor.postDelayed(task,4_010);true
                    } catch(error:Exception) {pending.cancel(lease,accepted.token);failed();false}
                })
            launches.add(host,state,address,accepted.token,{host.add(appGroup,it)},latest,send)
            val returning=state.lifecycle.controlOwnership==ControlOwnership.EXTERNAL_APP
            val row=host.row("hyperears.rayneo.official.open",if(returning)"返回 HyperEars 控制" else "打开雷鸟 AR 眼镜")
            host.summary(row,if(returning)"结束本次交接" else "更多设置与文件传输")
            host.enable(row,if(returning)canReturn(accepted) else writable(state))
            host.onClick(row) {
                val fresh=current()?.takeIf {it.token==accepted.token} ?: return@onClick
                val ok=if(returning&&canReturn(fresh))VendorHandoffLauncher.returnControl(address,environment.sessionTokenProvider,environment.controlSender)
                else if(!returning&&writable(fresh.state))VendorHandoffLauncher.open(anchor.context,address,
                    fresh.state.adapter?.controlApps.orEmpty(),environment.stateProvider,environment.sessionTokenProvider,
                    environment.observeState,environment.controlSender)
                else false
                if(!ok)host.summary(row,"打开失败，请检查应用安装和设备连接")
            }
            host.add(official,row)
        } catch(error:Exception) {
            ModuleLog.warn("Settings","native RayNeo rows failed",error);close()
        } finally {host.endUpdate()}
    }
    fun reconcile()=render()
    private fun failed() {android.widget.Toast.makeText(anchor.context,"修改失败",android.widget.Toast.LENGTH_SHORT).show()}
    fun close() {
        if(closed)return
        closed=true;launches.close();pending.reset();passwordDialog?.dismiss()
        timeouts.forEach(anchor::removeCallbacks);timeouts.clear()
        categories.forEach {runCatching {host.detach(it)}};categories.clear();host.dispose()
    }
}
