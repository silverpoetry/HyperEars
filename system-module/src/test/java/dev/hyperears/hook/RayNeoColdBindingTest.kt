package dev.hyperears.hook
import android.app.Activity
import android.os.Binder
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.*
import dev.hyperears.integration.*
import dev.hyperears.presentation.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class RayNeoColdBindingTest {
    @Test fun firstAsyncStateRestoresRowsButDestroyedPageDoesNot() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val anchor=View(activity).also(activity::setContentView)
        val screen=PreferenceManager(activity).createPreferenceScreen(activity)
        val stock=Preference(activity).apply {key="stock"};screen.addPreference(stock)
        val host=SettingsPreferenceHost(javaClass.classLoader!!,activity,screen)
        val address="02:00:00:00:00:01"
        val state=EarbudState(address=address,adapter=RayNeoX3ProAdapter().snapshot(),
            lifecycle=DeviceLifecycle(systemProfile=SystemProfileState.CONNECTED))
        var current:SettingsHeadsetState.Record?=null
        val env=MiLinkCardEnvironment(javaClass.classLoader!!,{state},{_,_->fail("unexpected write")},{_,_->{}},{"token"})
        val binding=SettingsHeadsetBinding(host,anchor,address,{address},{current},env)
        binding.render();assertEquals(1,screen.preferenceCount)
        current=SettingsHeadsetState.Record(state,"token",NativePresentationMetadata(HeadsetConnection("connection",1),NativeAdmission.HYPEREARS),Binder())
        binding.render();assertNotNull(screen.findPreference<Preference>("hyperears.rayneo.shortcuts"))
        current=null;binding.render();assertEquals(1,screen.preferenceCount)
        current=SettingsHeadsetState.Record(state,"next",NativePresentationMetadata(HeadsetConnection("connection2",2),NativeAdmission.HYPEREARS),Binder())
        binding.render();assertNotNull(screen.findPreference<Preference>("hyperears.rayneo.shortcuts"))
        binding.close();binding.render();assertEquals(1,screen.preferenceCount)
    }
    @Test fun customRowsAreMeasuredAndRecycledWithoutHidingStockContent() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val root=LinearLayout(activity);val stock=TextView(activity).apply{text="stock"};root.addView(stock)
        val holder=PreferenceViewHolder.createInstanceForTests(root)
        val row=Any();val content=TextView(activity).apply{text="BBLL myDV 红果TV 浮窗"}
        CustomPreferenceRows.put(row,content,60)
        CustomPreferenceRows.bind(row,holder)
        root.measure(View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(100,View.MeasureSpec.EXACTLY))
        root.layout(0,0,600,100)
        assertTrue(content.width>0);assertTrue(content.height>0);assertEquals(View.GONE,stock.visibility)
        CustomPreferenceRows.restore(holder)
        assertEquals(View.VISIBLE,stock.visibility);assertNull(content.parent)
        CustomPreferenceRows.remove(row)
    }
}
