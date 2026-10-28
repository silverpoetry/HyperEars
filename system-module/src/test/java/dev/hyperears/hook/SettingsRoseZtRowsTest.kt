package dev.hyperears.hook

import android.app.Activity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import dev.hyperears.integration.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class SettingsRoseZtRowsTest {
    @Test fun sourceI5NativeRowsUseActualReportsAndPreserveInvertedWear() {
        val a=RoseEarfreeI5Adapter(sourceQualified=true)
        a.receive(report(2,1,0)+report(12,1,0)+report(14,3,0))
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val screen=PreferenceManager(activity).createPreferenceScreen(activity)
        val host=SettingsPreferenceHost(javaClass.classLoader!!,activity,screen)
        val group=host.category("test","Test").also{host.add(screen,it)}
        val address="02:00:00:00:00:11"
        var current=EarbudState(address=address,adapter=a.snapshot(),features=a.runtimeState().features,
            lifecycle=DeviceLifecycle(SystemProfileState.CONNECTED,PrivateTransportState.CONNECTED,ProtocolHandshakeState.CONFIRMED))
        val sent=mutableListOf<ControlRequest>()
        SettingsRoseZtRows.add(host,current,address,{host.add(group,it)},{current},{sent+=it},{_,action->action()})
        fun row(key:String)=screen.findPreference<Preference>("hyperears.zt.$key") as ListPreference
        assertEquals(listOf("弱水经典","日系柔情","乐器大师","清新空灵"),row("eq").entries.map(CharSequence::toString))
        assertEquals(listOf("开启","关闭"),row("wear_detection").entries.map(CharSequence::toString))
        assertEquals("0",row("wear_detection").value)
        assertFalse(row("wear_detection").callChangeListener("1"))
        assertEquals(RoseZtControlRequest.SetValue("I5_SOURCE","WEAR_DETECTION",1),sent.last())
        assertEquals("1",row("prompt").value)
        assertFalse(row("prompt").callChangeListener("5"))
        assertEquals(RoseZtControlRequest.SetValue("I5_SOURCE","PROMPT",5),sent.last())
        assertEquals("1",row("prompt").value)
        current=current.copy(lifecycle=current.lifecycle.copy(controlOwnership=ControlOwnership.EXTERNAL_APP,externalControlApp=ControlAppCatalog.roseLink))
        row("prompt").callChangeListener("2");assertEquals(2,sent.size)
    }
    private fun report(group:Int,command:Int,value:Int):ByteArray {
        val b=listOf(9,255,0,0,1,group,command,11,0,value)
        return (b+(b.sum() and 255)).map(Int::toByte).toByteArray()
    }
}
