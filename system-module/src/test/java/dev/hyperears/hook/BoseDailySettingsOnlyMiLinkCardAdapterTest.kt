package dev.hyperears.hook

import android.app.Application
import android.content.ContextWrapper
import android.content.res.Resources
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.miui.circulate.world.headset.ui.HeadsetControlAncItemView
import com.miui.circulate.world.headset.ui.HeadsetSelectCardView
import com.miui.circulate.world.headset.ui.HeadsetSelectItemView
import dev.hyperears.integration.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class BoseDailySettingsOnlyMiLinkCardAdapterTest {
    @Test fun settingsCarrierRestoresBothHostLayoutsWithoutNoiseCommands() {
        NativeAncModeLayout.entries.forEach { layout ->
            val app = RuntimeEnvironment.getApplication()
            val ids = mapOf("anc_title" to 101, "anc_icon" to 102, "tools_text" to 103,
                "tools_icon" to 104, "anc_card" to 105, "anc_card_title" to 106,
                "anc_select_card" to 107, "anc_card_text" to 108, "anc_clear" to 109,
                "anc_noise_cancel" to 110, "anc_off" to 111)
            @Suppress("DEPRECATION")
            val resources = object : Resources(app.assets, app.resources.displayMetrics, app.resources.configuration) {
                override fun getIdentifier(name: String?, type: String?, pkg: String?): Int = ids[name] ?: 0
            }
            val context = object : ContextWrapper(app) { override fun getResources() = resources }
            val root = LinearLayout(context)
            val title = TextView(context).apply { id = ids.getValue(layout.titleId); text = "Original" }
            val row = if (layout == NativeAncModeLayout.SELECT_CARD) HeadsetSelectCardView(context) else LinearLayout(context)
            row.id = ids.getValue(layout.containerId)
            val originals = List(3) { index ->
                val item = if (layout == NativeAncModeLayout.SELECT_CARD) HeadsetSelectItemView(context) else HeadsetControlAncItemView(context)
                item.apply { id = 109 + index; layoutParams = LinearLayout.LayoutParams(0, 48, 1f) }
            }
            root.addView(title); root.addView(row); originals.forEach(row::addView)
            val address = "00:11:22:33:44:55"
            var state = EarbudState(address = address, lifecycle = DeviceLifecycle(
                systemProfile = SystemProfileState.CONNECTED,
                privateTransport = PrivateTransportState.CONNECTED,
                protocolHandshake = ProtocolHandshakeState.CONFIRMED,
            )).withFeature(BoseDailySettingsFeatureState(multipoint = false))
            val sent = mutableListOf<ControlRequest>()
            var token = "session-1"
            var observed = 0
            var released = 0
            val adapter = requireNotNull(MiLinkCardAdapterRegistry.resolve(BoseDailyPresentationIds.SETTINGS_ONLY))
            val binding = requireNotNull(adapter.bind(root, address, MiLinkCardEnvironment(
                javaClass.classLoader!!, { state }, { _, request -> sent += request }, { _, _ -> observed++; { released++ } },
                sessionTokenProvider = { token },
            )))
            binding.render(state)
            assertEquals(1, row.childCount)
            assertEquals("耳机设置", title.text.toString())
            assertTrue(row.getChildAt(0).isEnabled)
            assertNull(state.features.get<AppleListeningModeFeatureState>())
            token = "session-2"
            row.getChildAt(0).performClick()
            assertEquals(0, observed)
            binding.render(state)
            row.getChildAt(0).performClick()
            assertEquals(1, observed)
            assertEquals(1, released) // Detached fixture opens then immediately releases the panel.
            state = state.copy(lifecycle = state.lifecycle.copy(privateTransport = PrivateTransportState.IDLE))
            binding.render(state)
            assertFalse(row.getChildAt(0).isEnabled)
            row.getChildAt(0).performClick()
            assertTrue(sent.isEmpty())
            binding.unbind(); binding.unbind()
            assertEquals("Original", title.text.toString())
            assertEquals(originals, (0 until row.childCount).map(row::getChildAt))
        }
    }
}
