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
class HonorAncDepthMiLinkCardAdapterTest {
    @Test fun bothNativeLayoutsKeepExistingModeListenersBeforeAndAfterDepthEntry() {
        NativeAncModeLayout.entries.forEach { layout ->
            val app = RuntimeEnvironment.getApplication()
            val ids = mapOf("anc_title" to 101, "anc_icon" to 102, "tools_text" to 103, "tools_icon" to 104,
                "anc_card" to 105, "anc_card_title" to 106, "anc_select_card" to 107, "anc_card_text" to 108,
                "anc_clear" to 109, "anc_noise_cancel" to 110, "anc_off" to 111)
            @Suppress("DEPRECATION")
            val resources = object : Resources(app.assets, app.resources.displayMetrics, app.resources.configuration) {
                override fun getIdentifier(name: String?, type: String?, pkg: String?) = ids[name] ?: 0
            }
            val context = object : ContextWrapper(app) { override fun getResources() = resources }
            val root = LinearLayout(context)
            val title = TextView(context).apply { id = ids.getValue(layout.titleId); text = "Original" }
            val row = if (layout == NativeAncModeLayout.SELECT_CARD) HeadsetSelectCardView(context) else LinearLayout(context)
            row.id = ids.getValue(layout.containerId)
            val sent = mutableListOf<ControlRequest>()
            val modes = listOf(NoiseMode.TRANSPARENCY, NoiseMode.ANC, NoiseMode.OFF)
            val originals = modes.mapIndexed { index, mode ->
                val item = if (layout == NativeAncModeLayout.SELECT_CARD) HeadsetSelectItemView(context) else HeadsetControlAncItemView(context)
                item.apply { id = 109 + index; layoutParams = LinearLayout.LayoutParams(0, 48, 1f)
                    setOnClickListener { sent += StandardControlRequest.SetNoiseMode(mode) } }
            }
            root.addView(title); root.addView(row); originals.forEach(row::addView)
            val address = "00:11:22:33:44:55"
            var state = EarbudState(address = address, adapter = HonorX5sProAdapter().snapshot().copy(
                presentationId = HonorX5sProAdapter.PRESENTATION_ID, supportedNoiseModes = modes.toSet()),
                lifecycle = DeviceLifecycle(systemProfile = SystemProfileState.CONNECTED,
                    privateTransport = PrivateTransportState.CONNECTED, protocolHandshake = ProtocolHandshakeState.CONFIRMED))
                .withFeature(NoiseModeFeatureState(NoiseMode.ANC))
            val adapter = requireNotNull(MiLinkCardAdapterRegistry.resolve(HonorX5sProAdapter.PRESENTATION_ID))
            val binding = requireNotNull(adapter.bind(root, address, MiLinkCardEnvironment(javaClass.classLoader!!,
                { state }, { _, request -> sent += request }, { _, _ -> {} }, { "session" })))
            binding.render(state)
            assertFalse(HonorAncDepthPolicy.available(state, address))
            originals.forEach { it.performClick() }
            assertEquals(modes.map(StandardControlRequest::SetNoiseMode), sent)
            state = state.withFeature(HonorAncDepthFeatureState(HonorAncDepth.LIGHT, HonorAncDepth.entries.toSet()))
            binding.render(state)
            assertTrue(HonorAncDepthPolicy.available(state, address))
            assertEquals(MiLinkNativeCardSurface.ANC_THREE_STATE, adapter.nativeSurface)
            modes.forEach { assertEquals(it, adapter.projectNativeNoiseMode(it)) }
            assertEquals(originals, (0 until row.childCount).map(row::getChildAt))
            binding.unbind(); binding.unbind()
            assertSame(root, title.parent); assertEquals("Original", title.text.toString())
            assertSame(root, row.parent)
            originals[2].performClick(); assertEquals(StandardControlRequest.SetNoiseMode(NoiseMode.OFF), sent.last())
        }
    }
}
