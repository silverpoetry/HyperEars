package dev.hyperears.hook

import android.app.Application
import android.content.ContextWrapper
import android.content.res.Resources
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.miui.circulate.world.headset.ui.HeadsetControlAncItemView
import com.miui.circulate.world.headset.ui.HeadsetSelectCardView
import com.miui.circulate.world.headset.ui.HeadsetSelectItemView
import dev.hyperears.integration.*
import dev.hyperears.integration.AppleListeningMode
import dev.hyperears.integration.AppleListeningModeFeatureState
import dev.hyperears.integration.AppleMiLinkPresentationIds
import dev.hyperears.integration.ControlAppSpec
import dev.hyperears.integration.ControlOwnership
import dev.hyperears.integration.ControlRequest
import dev.hyperears.integration.DeviceFeatureSnapshot
import dev.hyperears.integration.DeviceLifecycle
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.NoiseMode
import dev.hyperears.integration.PrivateTransportState
import dev.hyperears.integration.ProtocolHandshakeState
import dev.hyperears.integration.SystemProfileState
import dev.hyperears.integration.withFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class TechnicsMiLinkCardAdapterTest {
    @Test fun bothLayoutsHaveThreeChoicesAndRestore() {
        NativeAncModeLayout.entries.forEach { layout ->
            val fixture = Fixture(layout)
            val binding = fixture.bind()
            val items = fixture.items()
            assertEquals(listOf("通透", "降噪", "关闭"), items.map { it.contentDescription.toString() })
            binding.render(fixture.current)
            assertEquals(listOf(false, true, false), items.map { it.isSelected })
            items[1].performClick()
            assertTrue(fixture.sent.isEmpty())
            items[2].performClick()
            assertEquals(listOf(StandardControlRequest.SetNoiseMode(NoiseMode.OFF)), fixture.sent)
            assertTrue(items[1].isSelected)
            binding.unbind(); binding.unbind()
            assertEquals(fixture.originals, fixture.items())
            assertEquals("Original title", fixture.title.text.toString())
            items[2].performClick()
            assertEquals(1, fixture.sent.size)
        }
    }
    @Test fun freshEvidenceAndSessionGateOldGesturesAndReconnect() {
        val fixture = Fixture()
        val binding = fixture.bind()
        binding.render(fixture.current)
        fixture.token = "next"
        fixture.items()[2].performClick()
        assertTrue(fixture.sent.isEmpty())
        binding.render(fixture.current)
        fixture.items()[2].performClick()
        assertEquals(1, fixture.sent.size)
        fixture.current = fixture.current.copy(address = "other")
        fixture.items()[2].performClick()
        assertEquals(1, fixture.sent.size)
        fixture.current = connected().copy(features = DeviceFeatureSnapshot())
        fixture.items()[2].performClick()
        binding.render(fixture.current)
        assertTrue(fixture.items().none { it.isEnabled })
        assertEquals(1, fixture.sent.size)
        binding.unbind()
    }
    @Test fun nativeConstructionFailureRestoresBothLayouts() {
        NativeAncModeLayout.entries.forEach { layout ->
            val fixture = Fixture(layout)
            val source = fixture.originals[2]
            source.removeView(source.findMiLinkView(layout.itemIconId))
            assertNull(fixture.tryBind())
            assertEquals(fixture.originals, fixture.items())
            assertEquals("Original title", fixture.title.text.toString())
        }
    }
    @Test fun failedModeSendRetainsAdvancedEntryAndConfirmedSelection() {
        val fixture = Fixture()
        fixture.current = fixture.current.withFeature(TechnicsLevelsFeatureState(NoiseMode.ANC, 80, 23))
        val binding = fixture.bind()
        binding.render(fixture.current)
        val wrapper = fixture.title.parent
        assertTrue(wrapper !== fixture.root)
        fixture.failSend = true
        fixture.items()[2].performClick()
        assertEquals(wrapper, fixture.title.parent)
        assertTrue(fixture.items()[1].isSelected)
        assertTrue(fixture.sent.isEmpty())
        fixture.failSend = false
        fixture.items()[2].performClick()
        assertEquals(listOf(StandardControlRequest.SetNoiseMode(NoiseMode.OFF)), fixture.sent)
        assertEquals(wrapper, fixture.title.parent)
        binding.unbind()
        assertEquals(fixture.root, fixture.title.parent)
    }

    private class Fixture(val layout: NativeAncModeLayout = NativeAncModeLayout.ORIGINAL) {
        private val app = RuntimeEnvironment.getApplication()
        private val ids = mapOf(
            "anc_title" to 101, "anc_icon" to 102, "tools_text" to 103, "tools_icon" to 104,
            "anc_card" to 105, "anc_card_title" to 106, "anc_select_card" to 107, "anc_card_text" to 108,
            "anc_clear" to 109, "anc_noise_cancel" to 110, "anc_off" to 111,
        )
        @Suppress("DEPRECATION")
        private val hostResources = object : Resources(app.assets, app.resources.displayMetrics, app.resources.configuration) {
            override fun getIdentifier(name: String?, defType: String?, defPackage: String?): Int = ids[name] ?: 0
        }
        private val context = object : ContextWrapper(app) {
            override fun getResources(): Resources = hostResources
        }
        val root = LinearLayout(context)
        val title = TextView(context).apply { id = ids.getValue(this@Fixture.layout.titleId); text = "Original title" }
        val container = if (layout == NativeAncModeLayout.SELECT_CARD) HeadsetSelectCardView(context) else LinearLayout(context)
        val originals = List(3) { index ->
            val item = if (layout == NativeAncModeLayout.SELECT_CARD) HeadsetSelectItemView(context) else HeadsetControlAncItemView(context)
            item.apply {
                id = 109 + index
                layoutParams = LinearLayout.LayoutParams(0, 48, 1f)
                (findMiLinkView(layout.itemIconId) as ImageView).setImageDrawable(ColorDrawable(0xff000001.toInt() + index))
            }
        }
        var current = connected()
        var token: String? = "session"
        var failSend = false
        val sent = mutableListOf<ControlRequest>()
        init {
            container.id = ids.getValue(layout.containerId)
            root.addView(title)
            root.addView(container)
            originals.forEach(container::addView)
        }
        fun bind(): MiLinkCardBinding = requireNotNull(tryBind())
        fun tryBind(): MiLinkCardBinding? =
            requireNotNull(MiLinkCardAdapterRegistry.resolve(TechnicsEarbudAdapter.PRESENTATION_ID)).bind(
                root, "00:11:22:33:44:55", MiLinkCardEnvironment(
                    javaClass.classLoader!!, { current }, { address, request ->
                        assertEquals("00:11:22:33:44:55", address)
                        if (failSend) error("synthetic IPC failure")
                        sent += request
                    }, { _, _ -> {} }, { token },
                ),
            )
        fun items(): List<View> = (0 until container.childCount).map(container::getChildAt)
    }

    companion object {
        private fun connected() = EarbudState(address = "00:11:22:33:44:55", lifecycle = DeviceLifecycle(
            systemProfile = SystemProfileState.CONNECTED,
            privateTransport = PrivateTransportState.CONNECTED,
            protocolHandshake = ProtocolHandshakeState.CONFIRMED,
        )).withFeature(NoiseModeFeatureState(NoiseMode.ANC)).withFeature(TechnicsLevelsFeatureState(NoiseMode.ANC, 80, 23))
    }
}
