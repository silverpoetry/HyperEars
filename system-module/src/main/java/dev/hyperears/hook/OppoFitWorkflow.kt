package dev.hyperears.hook
import android.os.Handler
import android.os.Looper
import android.content.Context
import android.widget.*
import dev.hyperears.integration.*
/** Explicit popup-owned lease. Cleanup writes require the original current owner/token. */
@android.annotation.SuppressLint("SetTextI18n")
internal class OppoFitWorkflow(private val context:Context,private val address:String,private val environment:MiLinkCardEnvironment,private val valid:(EarbudState)->Boolean) {
    val view=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
    private val handler=Handler(Looper.getMainLooper())
    private var owned:Pair<Int,Long>?=null
    private var message=""
    private var closed=false
    private val timeout=Runnable {if(!closed){stop();message="检测超时，请调整耳塞后重试。";render(environment.stateProvider(address))}}
    private fun stop(){val current=owned?:return;owned=null;handler.removeCallbacks(timeout);val state=environment.stateProvider(address)
        if(valid(state)&&state.features.get<OppoFitFeatureState>()?.productId==current.first)runCatching{environment.controlSender(address,OppoFitControlRequest.Stop(current.first,current.second))}}
    fun close(){if(closed)return;stop();closed=true;handler.removeCallbacks(timeout)}
    fun render(state:EarbudState) {
        if(closed)return
        if(!valid(state)){close();return}
        val feature=state.features.get<OppoFitFeatureState>()
        view.removeAllViews();view.visibility=if(feature?.advertised==true)android.view.View.VISIBLE else android.view.View.GONE
        if(feature==null)return
        if(feature.timedOut && owned?.second==feature.resultLease)message="检测超时，请调整耳塞后重试。"
        if(owned?.first!=feature.productId){stop()}
        if(owned!=null && (feature.resultLease==owned?.second || !feature.ready)) {owned=null;handler.removeCallbacks(timeout)}
        view.addView(TextView(context).apply{text="耳塞贴合度检测"})
        view.addView(TextView(context).apply{text=if(feature.ready)"请保持双耳佩戴，检测将在 16 秒内结束。"else"请将两只耳机戴好，再刷新佩戴状态。"})
        val leftResult=feature.leftResult;val rightResult=feature.rightResult
        if(owned==null && leftResult!=null&&rightResult!=null)view.addView(TextView(context).apply{text="左耳：${quality(leftResult)}；右耳：${quality(rightResult)}"})
        feature.error?.takeIf{owned==null}?.let{error->view.addView(TextView(context).apply{text="耳机拒绝检测，状态码 $error"})}
        view.addView(TextView(context).apply{text=if(owned!=null)"已发送检测请求，等待耳机结果。"else message})
        view.addView(Button(context).apply{text="开始检测";contentDescription="oppo.fit.start";isEnabled=feature.ready&&owned==null
            setOnClickListener {
                val latest=environment.stateProvider(address)
                if(!valid(latest)||latest.features.get<OppoFitFeatureState>()!=feature||!feature.ready||owned!=null)return@setOnClickListener
                val lease=System.nanoTime().and(Long.MAX_VALUE).coerceAtLeast(1L);owned=feature.productId to lease;message=""
                runCatching{environment.controlSender(address,OppoFitControlRequest.Start(feature,lease))}.onFailure{owned=null}
                if(owned!=null)this@OppoFitWorkflow.handler.postDelayed(timeout,16000)
                render(environment.stateProvider(address))
            }})
        view.addView(Button(context).apply{text="停止检测";contentDescription="oppo.fit.stop";isEnabled=owned!=null;setOnClickListener{stop();message="已请求停止检测。";render(environment.stateProvider(address))}})
        view.addView(Button(context).apply{text="刷新佩戴状态";isEnabled=owned==null;setOnClickListener{if(valid(environment.stateProvider(address)))environment.controlSender(address,StandardControlRequest.Refresh)}})
    }
    private fun quality(value:Int)=when(value){0->"一般";1->"良好";6->"较差";else->"未知"}
}
