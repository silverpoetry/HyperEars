package dev.hyperears.presentation

import dev.hyperears.settings.BatterySpeechMode
import org.junit.Assert.*
import org.junit.Test

class BatterySpeechUtteranceTest {
    private class Scheduler:SpeechScheduler {
        val posted=ArrayDeque<Runnable>();val deadlines=mutableListOf<Runnable>()
        override fun now()=100L
        override fun post(task:Runnable){posted+=task}
        override fun after(task:Runnable,milliseconds:Long){assertEquals(10_000L,milliseconds);deadlines+=task}
        override fun cancel(task:Runnable){deadlines.remove(task)}
        fun flush(){while(posted.isNotEmpty())posted.removeFirst().run()}
        fun expire(){deadlines.toList().forEach{it.run()}}
    }
    private class Gate:SpeechAudioGate {
        var allowed=true;var changed:(()->Unit)?=null;var focusLoss:(()->Unit)?=null
        var grant=true;var reentrantLoss=false;var abandons=0;var closes=0
        override fun allowed(address:String,mode:BatterySpeechMode)=allowed
        override fun watch(changed:()->Unit):Boolean {this.changed=changed;return true}
        override fun requestFocus(lost:()->Unit):Boolean {focusLoss=lost;if(reentrantLoss)lost();return grant}
        override fun abandonFocus(){abandons++}
        override fun close(){closes++;changed=null}
    }
    private class Engine:SpeechEngine {
        var beforePrepare:(()->Unit)?=null
        var prepare=true;var stops=0;var shutdowns=0;var spoken=0;var id=""
        override fun prepare():Boolean {beforePrepare?.invoke();return prepare}
        override fun speak(text:String,volume:Float,utteranceId:String):Boolean {spoken++;id=utteranceId;return true}
        override fun stop(){stops++}
        override fun shutdown(){shutdowns++}
    }
    private class Fixture {
        val scheduler=Scheduler();val gates=mutableListOf<Gate>();val engines=mutableListOf<Engine>()
        val init=mutableListOf<(Boolean)->Unit>();val done=mutableListOf<(String)->Unit>()
        var sync=false;var valid=true;var finished=0
        val host=BatterySpeechUtterance(scheduler,SpeechEngineFactory { initialized,completed ->
            init+=initialized;done+=completed
            Engine().also{engines+=it;if(sync)initialized(true)}
        },{Gate().also{gates+=it}})
        fun start()=host.speak("AA:BB:CC:DD:EE:01",BatterySpeechMode.ON,.5f,
            BatterySpeechMessage(true,emptyList()),{valid},{finished++})
    }
    @Test fun synchronousInitializationSpeaksOnceThenDoneReleasesEveryOwnedResource() {
        val f=Fixture().apply{sync=true};assertTrue(f.start());f.scheduler.flush()
        assertEquals(1,f.engines.single().spoken)
        f.init.single()(true);f.scheduler.flush();assertEquals(1,f.engines.single().spoken)
        f.done.single()(f.engines.single().id);f.scheduler.flush()
        assertEquals(1,f.engines.single().shutdowns);assertEquals(1,f.gates.single().abandons)
        assertTrue(f.scheduler.deadlines.isEmpty());assertEquals(1,f.finished)
        f.host.stop();assertEquals(1,f.finished)
    }
    @Test fun staleInitializationAndCompletionCannotCloseNewEngine() {
        val f=Fixture();assertTrue(f.start());val old=f.engines.single()
        f.host.stop();assertTrue(f.start());val newer=f.engines.last()
        f.init.first()(true);f.done.first()("hyperears-battery-1");f.scheduler.flush()
        assertEquals(1,old.shutdowns);assertEquals(0,newer.shutdowns);assertEquals(0,newer.spoken)
        f.init.last()(true);f.scheduler.flush();assertEquals(1,newer.spoken)
        f.host.stop();assertEquals(1,newer.shutdowns)
    }
    @Test fun focusDenialAndReentrantLossNeverQueueSpeechAndReleaseGrantedFocus() {
        for(reentrant in listOf(false,true)) {
            val f=Fixture();assertTrue(f.start())
            f.gates.single().apply{grant=reentrant;reentrantLoss=reentrant}
            f.init.single()(true);f.scheduler.flush()
            assertEquals(0,f.engines.single().spoken);assertEquals(1,f.engines.single().shutdowns)
            assertEquals(if(reentrant)1 else 0,f.gates.single().abandons)
            assertTrue(f.scheduler.deadlines.isEmpty())
        }
    }
    @Test fun prepareReentrantCancellationCannotRequestFocusOrLeakItsEngine() {
        val f=Fixture();assertTrue(f.start())
        f.engines.single().beforePrepare={f.host.stop()}
        f.init.single()(true);f.scheduler.flush()
        assertNull(f.gates.single().focusLoss)
        assertEquals(0,f.engines.single().spoken);assertEquals(1,f.engines.single().shutdowns)
        assertEquals(1,f.finished);assertTrue(f.scheduler.deadlines.isEmpty())
    }
    @Test fun delayedInitSettingsChangeRouteLossLanguageFailureAndDeadlineAreTerminal() {
        for(reason in listOf("valid","route","language","deadline","focus")) {
            val f=Fixture();assertTrue(f.start())
            when(reason) {
                "valid"->f.valid=false
                "route"->{f.gates.single().allowed=false;f.gates.single().changed!!.invoke()}
                "language"->f.engines.single().prepare=false
                "deadline"->f.scheduler.expire()
            }
            f.init.single()(true);f.scheduler.flush()
            if(reason=="focus")f.gates.single().focusLoss!!.invoke()
            assertEquals(1,f.engines.single().shutdowns);assertTrue(f.scheduler.deadlines.isEmpty())
            assertEquals(1,f.finished)
        }
    }
}
