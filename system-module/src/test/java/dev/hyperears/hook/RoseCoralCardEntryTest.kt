package dev.hyperears.hook

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.hyperears.integration.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class RoseCoralCardEntryTest {
    @Test fun sameCardReconnectCreatesFreshPanelAndFactoryFailureCleansOldPanel() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = LinearLayout(activity)
        val title = TextView(activity).apply { text = "Original" }
        root.addView(title)
        activity.setContentView(root)
        val address = "00:11:22:33:44:55"
        var state = EarbudState(address = address, lifecycle = DeviceLifecycle(
            systemProfile = SystemProfileState.CONNECTED, privateTransport = PrivateTransportState.CONNECTED,
            protocolHandshake = ProtocolHandshakeState.CONFIRMED,
        )).withFeature(RoseCoralSettingsFeatureState(gameMode = false))
        var token: String? = "first"
        var opened = 0
        var closed = 0
        var failFactory = false
        val environment = MiLinkCardEnvironment(javaClass.classLoader!!, { state }, { _, _ -> fail("unexpected write") },
            { _, _ -> {} }, { token })
        val binding = requireNotNull(AppleSettingsCardEntry.bind(title, root, address, environment,
            RoseCoralCardPolicy::available, { _, _, _ ->
                if (failFactory) error("synthetic factory failure")
                opened++
                object : MiLinkToggleDetails {
                    var dismissed = false
                    override fun render(state: EarbudState) = Unit
                    override fun dismiss() { if (!dismissed) { dismissed = true; closed++ } }
                }
            }))
        val entry = (title.parent as ViewGroup).getChildAt(1)
        binding.render(state)
        entry.performClick()
        assertEquals(1, opened)
        token = "second"
        entry.performClick()
        assertEquals(1, opened); assertEquals(1, closed)
        binding.render(state)
        entry.performClick()
        assertEquals(2, opened)
        failFactory = true
        entry.performClick()
        assertEquals(2, closed)
        failFactory = false
        entry.performClick()
        assertEquals(3, opened)
        activity.setContentView(View(activity))
        assertEquals(3, closed)
        binding.unbind()
        assertSame(root, title.parent)
        assertEquals(1, root.childCount)
        entry.performClick()
        assertEquals(3, opened)
    }
}
