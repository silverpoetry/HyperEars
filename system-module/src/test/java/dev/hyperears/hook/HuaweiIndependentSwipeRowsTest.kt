package dev.hyperears.hook
import android.app.Activity
import androidx.preference.*
import dev.hyperears.integration.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],manifest=Config.NONE)
class HuaweiIndependentSwipeRowsTest {
    private fun hex(s:String)=s.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
    @Test fun nativePairShowsUnknownCurrentAndRejectsChangedSiblingAndLostOwner() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val screen=PreferenceManager(activity).createPreferenceScreen(activity)
        val host=SettingsPreferenceHost(javaClass.classLoader!!,activity,screen)
        val address="00:11:22:33:44:55"
        val adapter=EarbudAdapterRegistry.resolve(EarbudIdentity("HUAWEI FreeArc",true))!!
        adapter.receive(hex("5A000E002B1F01010702010003030001FF9574"))
        var state=EarbudState(adapter=adapter.snapshot(),address=address,features=adapter.runtimeState().features,
            lifecycle=DeviceLifecycle(systemProfile=SystemProfileState.CONNECTED,
                privateTransport=PrivateTransportState.CONNECTED,protocolHandshake=ProtocolHandshakeState.CONFIRMED))
        val sent=mutableListOf<ControlRequest>()
        SettingsVendorRows.add(host,state,address,{screen.addPreference(it as Preference)},{state},{sent+=it})
        val left=screen.findPreference<ListPreference>("hyperears.huawei.SWIPE.LEFT")!!
        val right=screen.findPreference<ListPreference>("hyperears.huawei.SWIPE.RIGHT")!!
        assertEquals("左耳滑动",left.title);assertEquals("右耳滑动",right.title)
        assertTrue(left.summary.toString().contains("未知值 7"))
        assertEquals(listOf("调节音量","切换曲目"),right.entries.map{it.toString()})
        assertFalse(right.callChangeListener("1"))
        assertEquals(1,sent.size)
        assertEquals(7,(sent.single() as HuaweiDailyControlRequest.SetGesture).expected!!.left)
        assertEquals("0",right.value)
        adapter.receive(hex("5A000E002B1F01010802010003030001FF0D20"))
        state=state.copy(features=adapter.runtimeState().features)
        right.callChangeListener("1");assertEquals(1,sent.size)
        state=state.copy(lifecycle=state.lifecycle.copy(privateTransport=PrivateTransportState.IDLE))
        right.callChangeListener("1");assertEquals(1,sent.size)
    }
    @Test fun profileDomainsAndIncompletePairAreVisibleWithoutInventedChoices() {
        for(name in listOf("HUAWEI FreeBuds 6i","HUAWEI FreeClip 2")) {
            val a=EarbudAdapterRegistry.resolve(EarbudIdentity(name,true))!!
            a.receive(hex("5A000E002B1F01010002010003030001FF24DF"))
            fun rows()=HuaweiDailyChoices.rows(EarbudState(adapter=a.snapshot(),features=a.runtimeState().features))
            assertEquals(2,rows().size)
            assertEquals(if(name.endsWith("6i"))setOf(0,1,-1)else setOf(0,-1),rows().first().choices.map{it.value}.toSet())
            a.receive(hex("5A0006002B1F0101006D99"))
            assertEquals(1,rows().size);assertTrue(rows().single().choices.isEmpty())
            assertEquals("调节音量",rows().single().currentLabel)
        }
    }
}
