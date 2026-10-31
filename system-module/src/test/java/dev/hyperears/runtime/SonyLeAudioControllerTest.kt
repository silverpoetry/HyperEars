package dev.hyperears.runtime
import android.os.Handler
import android.os.Looper
import dev.hyperears.integration.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.Closeable
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class SonyLeAudioControllerTest {
    @Test fun realHistoryAndNotificationRequiredAndLostSessionNeverWrites() {
        val identity=SonyLeAudioIdentity("AA:BB:CC:DD:EE:01",listOf("AA:BB:CC:DD:EE:02"))
        var feature:SonyLeAudioFeatureState?=SonyLeAudioFeatureState(supported=true,classicAddress=identity.classic,
            leAddresses=identity.le,available=true,enabled=false,pairedHistory=0,historySequence=8)
        var ready:((Boolean)->Unit)?=null
        val progress=mutableListOf<SonyLeAudioOperation>()
        val sent=mutableListOf<ControlRequest>();val results=mutableListOf<SonyLeAudioController.Result>()
        val controller=SonyLeAudioController({a,t->if(a==identity.classic && t=="s")feature else null},
            {r,_,_->sent+=r;true},{cb->ready=cb;Closeable{}},{identity.le.toSet()},results::add,Handler(Looper.getMainLooper()),progress={_,_,value->progress+=value})
        val request=SonyControlRequest.SetLeAudio(identity,false,true)
        assertTrue(controller.begin(identity.classic,"s",request));shadowOf(Looper.getMainLooper()).idle()
        assertTrue(sent.isEmpty());ready!!(true);shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(SonyControlRequest.ReadLeAudioHistory(identity)),sent)
        controller.observe(identity.classic,"s");shadowOf(Looper.getMainLooper()).idle();assertEquals(1,sent.size)
        feature=feature!!.copy(historySequence=9)
        controller.observe(identity.classic,"s");shadowOf(Looper.getMainLooper()).idle();assertEquals(request,sent.last())
        feature=feature!!.copy(enabled=true)
        controller.observe(identity.classic,"s");shadowOf(Looper.getMainLooper()).idle();assertTrue(results.isEmpty())
        feature=feature!!.copy(notificationSequence=1,notifiedEnabled=true)
        val notification=feature
        feature=null // Classic disconnect happens before the posted observer runs
        controller.observe(identity.classic,"s",notification);shadowOf(Looper.getMainLooper()).idle()
        assertTrue(results.single().confirmed);assertFalse(results.single().pairingRequired)
        assertEquals(listOf(SonyLeAudioPhase.WAITING_PROFILE,SonyLeAudioPhase.WAITING_HISTORY,
            SonyLeAudioPhase.WAITING_CONFIRMATION,SonyLeAudioPhase.SETTING_CONFIRMED),progress.map{it.phase})
        feature=notification!!.copy(enabled=false)
        assertTrue(controller.begin(identity.classic,"s",request));shadowOf(Looper.getMainLooper()).idle()
        feature=null;ready!!(true);shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2,sent.size);assertFalse(results.last().confirmed)
        controller.close()
    }
}
