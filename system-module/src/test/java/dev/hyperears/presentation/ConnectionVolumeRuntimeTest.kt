package dev.hyperears.presentation

import dev.hyperears.integration.*
import dev.hyperears.settings.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionVolumeRuntimeTest {
    private val address="AA:BB:CC:DD:EE:01"
    private val producer=Any()
    private fun state(revision:Long=1)=EarbudState(address=address,revision=revision,lifecycle=DeviceLifecycle(systemProfile=SystemProfileState.CONNECTED))
    private fun metadata(replay:Boolean=false,admission:NativeAdmission=NativeAdmission.HYPEREARS,id:String="one",time:Long=100)=
        NativePresentationMetadata(HeadsetConnection(id,time),admission,replay)
    private class Host:ConnectionVolumeHost {
        var time=100L;var volume=7;var routed=true;var normal=true;var starts=0;var stops=0
        var failStart=false;var failWrite=false;var beforeWrite:(()->Unit)?=null
        var changed:(()->Unit)?=null
        val tasks=linkedMapOf<Runnable,Long>()
        val writes=mutableListOf<Int>()
        override fun now()=time
        override fun currentVolume()=volume
        override fun normalMode()=normal
        override fun start(changed:()->Unit):Boolean {starts++;this.changed=changed;return !failStart}
        override fun stop(){stops++;changed=null}
        override fun routeMatches(address:String)=routed
        override fun schedule(task:Runnable,delayMillis:Long){tasks[task]=time+delayMillis}
        override fun cancel(task:Runnable){tasks.remove(task)}
        override fun apply(address:String,percent:Int,expectedVolume:Int,valid:()->Boolean):Boolean {
            beforeWrite?.invoke()
            if(!valid()||!normal||!routed||volume!=expectedVolume)return false
            if(failWrite)error("host failure")
            writes+=percent;volume=percent;return true
        }
        fun change(){changed?.invoke()}
        fun expire(){time=10_100;tasks.keys.toList().forEach{it.run()}}
    }
    @Test fun appliesOnceAndNeverRestoresOrRearmsOnSamePhysicalConnection() {
        val host=Host();var settings=ModuleSettings(connectionVolume=ConnectionVolumePreset.P30)
        val runtime=ConnectionVolumeRuntime(host){settings}
        runtime.update(state(),"s",metadata(),producer)
        assertEquals(listOf(30),host.writes);assertNull(host.changed);assertTrue(host.tasks.isEmpty())
        runtime.update(state(2),"s",metadata(),producer)
        runtime.update(state(3),"new-session",metadata(),producer)
        runtime.remove(address);assertEquals(listOf(30),host.writes)
        runtime.update(state(4),"new-session",metadata(),producer)
        assertEquals(1,host.writes.size)
        host.time=200;runtime.update(state(),"t",metadata(id="two",time=200),producer)
        assertEquals(listOf(30,30),host.writes)
        settings=settings.copy(connectionVolume=ConnectionVolumePreset.OFF);runtime.settingsChanged()
        assertEquals(listOf(30,30),host.writes)
    }
    @Test fun replayOffAndMidConnectionSettingsOnlyEstablishConsumedBaseline() {
        val host=Host();var settings=ModuleSettings()
        val runtime=ConnectionVolumeRuntime(host){settings}
        runtime.update(state(),"s",metadata(),producer)
        settings=settings.copy(connectionVolume=ConnectionVolumePreset.P40);runtime.settingsChanged()
        runtime.update(state(2),"s",metadata(),producer)
        assertTrue(host.writes.isEmpty())
        runtime.update(state(),"t",metadata(replay=true,id="two",time=200),producer)
        host.time=200;runtime.update(state(3),"t",metadata(id="two",time=200),producer)
        assertTrue(host.writes.isEmpty())
    }
    @Test fun delayedRouteAndAdmissionAreBoundedByOriginalConnectionDeadline() {
        val host=Host().apply{routed=false}
        val runtime=ConnectionVolumeRuntime(host){ModuleSettings(connectionVolume=ConnectionVolumePreset.P20)}
        runtime.update(state(),"s",metadata(admission=NativeAdmission.UNKNOWN),producer)
        assertEquals(1,host.starts);assertEquals(listOf(10_100L),host.tasks.values.toList())
        host.time=500;host.routed=true;host.change();assertTrue(host.writes.isEmpty())
        runtime.update(state(2),"s",metadata(),producer);assertEquals(listOf(20),host.writes)
        host.time=600;host.routed=false;runtime.update(state(),"t",metadata(id="two",time=600),producer)
        host.time=10_600;host.tasks.keys.toList().forEach{it.run()}
        host.routed=true;host.change();runtime.update(state(2),"t",metadata(id="two",time=600),producer)
        assertEquals(1,host.writes.size);assertNull(host.changed);assertTrue(host.tasks.isEmpty())
    }
    @Test fun manualVolumeCallConversationSessionAndOwnerChangesCancelWithoutRetry() {
        for(reason in listOf("volume","call","conversation","session","system","external","paused","disconnect","settings","producer")) {
            val host=Host().apply{routed=false};var settings=ModuleSettings(connectionVolume=ConnectionVolumePreset.P30)
            val runtime=ConnectionVolumeRuntime(host){settings}
            runtime.update(state(),"s",metadata(),producer)
            when(reason) {
                "volume"->{host.volume=8;host.change()}
                "call"->{host.normal=false;host.change()}
                "conversation"->runtime.update(state(2).withFeature(AppleConversationFeatureState(1,1)),"s",metadata(),producer)
                "session"->runtime.update(state(2),"t",metadata(),producer)
                "system"->runtime.update(state(2),"s",metadata(admission=NativeAdmission.SYSTEM),producer)
                "external"->runtime.update(state(2).copy(lifecycle=DeviceLifecycle(systemProfile=SystemProfileState.CONNECTED,
                    controlOwnership=ControlOwnership.EXTERNAL_APP,externalControlApp=ControlAppCatalog.andPods)),"s",metadata(),producer)
                "paused"->{settings=settings.copy(modulePaused=true);runtime.settingsChanged()}
                "disconnect"->runtime.remove(address)
                "settings"->{settings=settings.copy(connectionVolume=ConnectionVolumePreset.P40);runtime.settingsChanged()}
                "producer"->runtime.clear()
            }
            host.routed=true;host.normal=true;host.change()
            assertTrue(reason,host.writes.isEmpty());assertNull(reason,host.changed);assertTrue(reason,host.tasks.isEmpty())
        }
    }
    @Test fun deadProducerOrOldPhysicalConnectionCannotWriteAndHistoryRemainsBounded() {
        val host=Host().apply{routed=false};var alive=true
        val runtime=ConnectionVolumeRuntime(host,producerAlive={alive}){ModuleSettings(connectionVolume=ConnectionVolumePreset.P30)}
        runtime.update(state(),"s",metadata(),producer)
        alive=false;host.routed=true;host.change()
        assertTrue(host.writes.isEmpty());assertNull(host.changed)
        alive=true;host.time=200
        runtime.update(state(),"t",metadata(id="two",time=200),producer)
        assertEquals(1,host.writes.size)
        runtime.update(state(99),"s",metadata(),producer)
        assertEquals(1,host.writes.size)
        for(i in 2..140)runtime.update(state().copy(address="AA:BB:CC:DD:EE:"+i.toString(16).padStart(2,'0')),
            "new",metadata(id="device$i",time=200),producer)
        assertEquals(128,host.writes.size)
    }
    @Test fun rechecksCurrentSettingAndRouteAtWriteBoundaryAndContainsHostFailures() {
        for(kind in listOf("setting","route","exception","start")) {
            val host=Host();var settings=ModuleSettings(connectionVolume=ConnectionVolumePreset.P30)
            val runtime=ConnectionVolumeRuntime(host){settings}
            when(kind) {
                "setting"->host.beforeWrite={settings=settings.copy(connectionVolume=ConnectionVolumePreset.P50)}
                "route"->host.beforeWrite={host.routed=false}
                "exception"->host.failWrite=true
                "start"->host.failStart=true
            }
            runtime.update(state(),"s",metadata(),producer)
            runtime.update(state(2),"s",metadata(),producer)
            assertTrue(kind,host.writes.isEmpty());assertNull(kind,host.changed);assertTrue(kind,host.tasks.isEmpty())
        }
    }
}
