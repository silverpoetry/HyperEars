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
class HuaweiDailyMiLinkCardAdapterTest {
    @Test fun advancedOnlyCarrierHidesNoiseRestoresBothLayoutsAndRejectsOwnershipLoss() {
        listOf(false, true).forEach { levelOnly ->
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
            val adapter = requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(deviceName = if (levelOnly) "HUAWEI FreeBuds Pro 3" else "HUAWEI FreeClip 2", standardHeadset = true)))
            adapter.receive(dev.hyperears.protocol.huawei.HuaweiFreebudsSppCodec.packet(
                if (levelOnly) 0x2B2A else 0x2B11, listOf(1 to if (levelOnly) byteArrayOf(0, 1) else byteArrayOf(1))))
            var state = EarbudState(adapter = adapter.snapshot(), address = address, lifecycle = DeviceLifecycle(
                systemProfile = SystemProfileState.CONNECTED,
                privateTransport = PrivateTransportState.CONNECTED,
                protocolHandshake = ProtocolHandshakeState.CONFIRMED,
            )).withFeature(HuaweiDailySettingsFeatureState(wearDetection = true))
            if (levelOnly) state = state.copy(features = adapter.runtimeState().features)
            val sent = mutableListOf<ControlRequest>()
            val card = requireNotNull(MiLinkCardAdapterRegistry.resolve(MiLinkCardPresentationId("huawei-daily-settings")))
            val binding = requireNotNull(card.bind(root, address, MiLinkCardEnvironment(
                javaClass.classLoader!!, { state }, { _, request -> sent += request }, { _, _ -> {} },
                sessionTokenProvider = { "session-1" },
            )))
            binding.render(state)
            assertEquals(4, row.childCount)
            assertTrue((0..2).all { row.getChildAt(it).visibility == if (levelOnly) View.VISIBLE else View.GONE })
            assertEquals("耳机设置", title.text.toString())
            assertTrue(row.getChildAt(3).isEnabled)
            if (levelOnly) assertNull(state.features.get<HuaweiDailySettingsFeatureState>())
            else assertNull(state.features.get<NoiseModeFeatureState>())
            state = state.copy(lifecycle = state.lifecycle.copy(controlOwnership = ControlOwnership.EXTERNAL_APP, externalControlApp = ControlAppCatalog.huaweiAiLife))
            binding.render(state)
            assertFalse(row.getChildAt(3).isEnabled)
            row.getChildAt(3).performClick()
            assertTrue(sent.isEmpty())
            binding.unbind(); binding.unbind()
            assertEquals("Original", title.text.toString())
            assertEquals(originals, (0 until row.childCount).map(row::getChildAt))
        }
        }
    }
}

