package dev.hyperears.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.hyperears.integration.*
import java.util.concurrent.ThreadLocalRandom

internal data class RoseFindUiSnapshot(val model:String,val available:Boolean,val targets:Set<RoseFindTarget>,val intent:RoseFindWrittenIntent?)

/** UI-owned backstop only; Bluetooth owns the primary deadline and successful-write evidence. */
internal class RoseFindDetails(
    private val context:Context,
    private val snapshot:()->RoseFindUiSnapshot?,
    private val send:(String,Long,RoseFindTarget)->Unit,
    private val refresh:()->Unit,
    private val text:(String)->TextView,
) {
    private val timer=Handler(Looper.getMainLooper())
    private var owned:Pair<String,Long>?=null
    private var written=false
    private var closed=false
    private val timeout=Runnable { stop();if(!closed)runCatching(refresh) }
    fun render(parent:LinearLayout,current:RoseFindUiSnapshot) {
        if(closed || !current.available || current.targets.isEmpty())return
        val lease=owned
        if(lease!=null) {
            if(current.model!=lease.first || current.intent?.let{it.lease!=lease.second}==true || written && current.intent==null)clear()
            else if(current.intent?.lease==lease.second)written=true
        }
        parent.addView(text("查找耳机"))
        parent.addView(text("请先取下耳机。查找请求最多保留 30 秒；设备是否响铃未获确认。"))
        val own=owned
        if(current.intent!=null) {
            parent.addView(text("请求已写入，设备未确认"))
            if(own!=null && own.first==current.model && own.second==current.intent.lease)parent.addView(Button(context).apply {
                text="停止查找";contentDescription="rose.find.stop"
                setOnClickListener {if(!closed){stop();runCatching(refresh)}}
            })
        } else if(own!=null)parent.addView(text("查找请求已提交，等待写入结果"))
        else current.targets.forEach { target ->
            parent.addView(Button(context).apply {
                text=when(target){RoseFindTarget.LEFT->"发送左耳查找请求";RoseFindTarget.RIGHT->"发送右耳查找请求";
                    RoseFindTarget.BOTH->"发送双耳查找请求";RoseFindTarget.HEADSET->"发送耳机查找请求";RoseFindTarget.STOP->"停止查找"}
                contentDescription="rose.find.$target"
                setOnClickListener {start(current.model,target)}
            })
        }
    }
    private fun start(model:String,target:RoseFindTarget) {
        if(closed || owned!=null || target==RoseFindTarget.STOP)return
        val current=runCatching(snapshot).getOrNull()?:return
        if(!current.available || current.model!=model || current.intent!=null || target !in current.targets)return
        val lease=ThreadLocalRandom.current().nextLong(1,Long.MAX_VALUE)
        owned=model to lease;written=false
        timer.postDelayed(timeout,30_000)
        if(runCatching{send(model,lease,target)}.isFailure)clear()
        if(!closed)runCatching(refresh)
    }
    private fun clear() {owned=null;written=false;timer.removeCallbacks(timeout)}
    private fun stop() {
        val previous=owned?:return
        clear() // Consume before a possibly synchronous state notification or repeated close.
        val current=runCatching(snapshot).getOrNull()?:return
        if(current.model!=previous.first || current.intent?.lease!=previous.second)return
        runCatching{send(previous.first,previous.second,RoseFindTarget.STOP)}
    }
    fun close() {if(closed)return;stop();closed=true;timer.removeCallbacks(timeout)}
}
