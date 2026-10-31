package dev.hyperears.runtime

import android.os.Handler
import android.os.Looper
import dev.hyperears.integration.*
import java.io.Closeable

/** Serial, session-bound orchestration; platform operations supplied by the Android owner. */
internal class SonyLeAudioController(
    private val current: (String,String) -> SonyLeAudioFeatureState?,
    private val send: (ControlRequest,String,String) -> Boolean,
    private val profile: ((Boolean) -> Unit) -> Closeable,
    private val bonded: () -> Set<String>,
    private val completed: (Result) -> Unit,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val now: () -> Long = { System.nanoTime()/1_000_000 },
    private val progress:(String,String,SonyLeAudioOperation)->Unit = {_,_,_->},
) : Closeable {
    data class Result(val address:String,val token:String,val confirmed:Boolean,val pairingRequired:Boolean,val reason:String)
    private val machine=SonyLeAudioSwitch(45_000)
    private data class Pending(val address:String,val token:String,val request:SonyControlRequest.SetLeAudio,
        val ticket:SonyLeAudioSwitch.Ticket,var history:Long=Long.MAX_VALUE,var notification:Long=Long.MAX_VALUE,
        var profile:Closeable?=null,var deadline:Runnable?=null)
    private var pending:Pending?=null
    private var closed=false

    @Synchronized fun begin(address:String,token:String,request:SonyControlRequest.SetLeAudio):Boolean {
        if(closed || pending!=null || !request.expectedIdentity.classic.equals(address,true))return false
        if(current(address,token)?.supports(request)!=true)return false
        val effect=machine.begin(token,request.enabled,now())?:return false
        val p=Pending(address,token,request,effect.ticket);pending=p
        progress(address,token,SonyLeAudioOperation(SonyLeAudioPhase.WAITING_PROFILE,request.enabled))
        handler.post {
            synchronized(this) {
                if(pending!==p || closed)return@synchronized
                p.deadline=Runnable { synchronized(this) { dispatch(p,machine.timeout(p.ticket,now())) } }.also {handler.postDelayed(it,45_000)}
                p.profile=profile { ready -> handler.post { synchronized(this) {
                    if(!valid(p))return@synchronized
                    val paired=runCatching { bonded().any { b->p.request.expectedIdentity.le.any {it.equals(b,true)} } }.getOrDefault(false)
                    dispatch(p,machine.profile(p.ticket,ready,paired,now()))
                } } }
            }
        }
        return true
    }

    fun observe(address:String,token:String,reported:SonyLeAudioFeatureState?=null) { handler.post { synchronized(this) {
        val p=pending?.takeIf {it.address.equals(address,true)&&it.token==token}?:return@synchronized
        val state=reported?:current(address,token)
        // Preserve an actual notification delivered just before the expected Classic disconnect.
        // This proves the setting only; it never proves that the replacement link connected.
        if(state?.identity==p.request.expectedIdentity && state.notificationSequence>p.notification)
            dispatch(p,machine.setting(p.ticket,state.notifiedEnabled,true,now()))
        if(pending!==p || !valid(p))return@synchronized
        if(state!=null && state.historySequence>p.history)dispatch(p,machine.history(p.ticket,state.pairedHistory,now()))
    } } }

    fun cancel(address:String,token:String) {handler.post {synchronized(this) {
        pending?.takeIf {it.address.equals(address,true)&&it.token==token}?.let {dispatch(it,machine.failed(it.ticket,"操作已取消"))}
    }}}

    private fun valid(p:Pending):Boolean {
        if(pending!==p || closed)return false
        val state=current(p.address,p.token)
        if(state?.identity!=p.request.expectedIdentity) {
            dispatch(p,machine.failed(p.ticket,"会话或耳机能力已变化"));return false
        }
        return true
    }
    private fun dispatch(p:Pending,effect:SonyLeAudioSwitch.Effect?) {
        if(pending!==p || effect==null)return
        when(effect) {
            is SonyLeAudioSwitch.Effect.History -> {
                if(!valid(p))return
                p.history=current(p.address,p.token)!!.historySequence
                progress(p.address,p.token,SonyLeAudioOperation(SonyLeAudioPhase.WAITING_HISTORY,p.request.enabled))
                if(!send(SonyControlRequest.ReadLeAudioHistory(p.request.expectedIdentity),p.address,p.token))
                    dispatch(p,machine.failed(p.ticket,"配对历史查询未发送，请稍后重试"))
            }
            is SonyLeAudioSwitch.Effect.Write -> {
                if(!valid(p))return
                p.notification=current(p.address,p.token)!!.notificationSequence
                progress(p.address,p.token,SonyLeAudioOperation(SonyLeAudioPhase.WAITING_CONFIRMATION,p.request.enabled))
                if(!send(p.request,p.address,p.token))dispatch(p,machine.failed(p.ticket,"切换命令未发送，请稍后重试"))
            }
            is SonyLeAudioSwitch.Effect.Complete -> finish(p,Result(p.address,p.token,true,effect.pairingRequired,"耳机已确认设置；连接状态需另行确认"))
            is SonyLeAudioSwitch.Effect.Failed -> finish(p,Result(p.address,p.token,false,false,effect.reason))
            is SonyLeAudioSwitch.Effect.Profile -> Unit
        }
    }
    private fun finish(p:Pending,result:Result) {
        pending=null;p.deadline?.let(handler::removeCallbacks);p.profile?.close()
        progress(p.address,p.token,SonyLeAudioOperation(if(result.confirmed)SonyLeAudioPhase.SETTING_CONFIRMED else SonyLeAudioPhase.FAILED,
            p.request.enabled,result.pairingRequired,result.reason))
        completed(result)
    }
    @Synchronized override fun close() {
        closed=true
        pending?.let {machine.cancel(it.token);it.deadline?.let(handler::removeCallbacks);it.profile?.close()}
        pending=null
    }
}
