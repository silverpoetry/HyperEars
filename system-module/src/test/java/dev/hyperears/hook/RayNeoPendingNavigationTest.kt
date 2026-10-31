package dev.hyperears.hook
import android.app.Activity
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class RayNeoPendingNavigationTest {
    @Test fun destroyedFragmentCancelsPendingRouteWhileActivityStaysAlive() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val view=View(activity).apply {visibility=View.INVISIBLE}
        val hook=SettingsHeadsetHook()
        val fragment=Any()
        val pendingType=SettingsHeadsetHook::class.java.declaredClasses.single {it.simpleName=="PendingDetails"}
        val ctor=pendingType.getDeclaredConstructor(String::class.java,Activity::class.java,View::class.java,Boolean::class.javaPrimitiveType).apply {isAccessible=true}
        val pending=ctor.newInstance("02:00:00:00:00:01",activity,view,true)
        fun field(name:String)=SettingsHeadsetHook::class.java.getDeclaredField(name).apply {isAccessible=true}.get(hook)
        @Suppress("UNCHECKED_CAST") val entries=field("pendingDetails") as MutableMap<Any,Any>
        @Suppress("UNCHECKED_CAST") val listHosts=field("listHosts") as MutableSet<Any>
        entries[fragment]=pending
        // Another Settings host keeps the receiver alive after this fragment leaves.
        listHosts.add(Any())
        SettingsHeadsetHook::class.java.getDeclaredMethod("release",Any::class.java).apply {isAccessible=true}.invoke(hook,fragment)
        assertTrue(entries.isEmpty());assertEquals(View.VISIBLE,view.visibility)
        SettingsHeadsetHook::class.java.getDeclaredMethod("routePendingDetails").apply {isAccessible=true}.invoke(hook)
        assertNull(shadowOf(activity).nextStartedActivity);assertFalse(activity.isFinishing)
    }
}
