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
import dev.hyperears.integration.AppleControlRequest
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
class AppleListeningMiLinkCardAdapterTest {
    @Test
    fun listeningPresentationHasANativeCardAdapter() {
        assertNotNull(MiLinkCardAdapterRegistry.resolve(AppleMiLinkPresentationIds.LISTENING))
    }

    @Test
    fun bothNativeLayoutsPreserveStockOrderIconsAndRestoreOriginals() {
        NativeAncModeLayout.entries.forEach { layout ->
            val fixture = Fixture(layout)
            val binding = fixture.bind()
            val items = fixture.items()
            assertEquals(4, items.size)
            assertEquals(listOf("通透", "降噪", "关闭", "自适应"), items.map { it.contentDescription.toString() })
            assertEquals(listOf(0xff000001.toInt(), 0xff000002.toInt(), 0xff000003.toInt(), 0xff000002.toInt()),
                items.map { ((it.findMiLinkView(layout.itemIconId) as ImageView).drawable as ColorDrawable).color })
            assertEquals(4, items.map { it.id }.toSet().size)
            assertTrue(items.none { it.isEnabled || it.isSelected })
            assertEquals(View.GONE, items[3].visibility)
            binding.render(fixture.current)
            assertEquals(listOf(false, true, false, false), items.map { it.isSelected })
            assertTrue(items.take(3).all { it.isEnabled })
            assertEquals(View.GONE, items[3].visibility)
            fixture.current = connected(AppleListeningMode.ADAPTIVE, AppleListeningMode.entries.toSet())
            binding.render(fixture.current)
            assertEquals(View.VISIBLE, items[3].visibility)
            assertTrue(items[3].isEnabled)
            assertEquals(listOf(false, false, false, true), items.map { it.isSelected })
            items.forEach { item ->
                assertEquals(item.isSelected, item.findMiLinkView(layout.itemTextId)!!.isSelected)
            }
            binding.unbind()
            binding.unbind()
            assertEquals(fixture.originals, fixture.items())
            assertEquals("Original title", fixture.title.text.toString())
            items.forEach { it.performClick() }
            assertTrue(fixture.sent.isEmpty())
        }
    }

    @Test
    fun clicksUseLatestStateTypedRequestsAndWaitForConfirmedSelection() {
        val fixture = Fixture()
        val binding = fixture.bind()
        binding.render(fixture.current)
        val items = fixture.items()
        items[1].performClick() // Current ANC is a no-op.
        items[3].performClick() // Unsupported adaptive is a no-op even if invoked directly.
        assertTrue(fixture.sent.isEmpty())
        items[0].performClick()
        assertEquals(listOf(AppleControlRequest.SetListeningMode(AppleListeningMode.TRANSPARENCY)), fixture.sent)
        assertTrue(items[1].isSelected)
        assertFalse(items[0].isSelected)
        fixture.current = connected(AppleListeningMode.TRANSPARENCY)
        items[0].performClick() // State changed since render: do not resend.
        assertEquals(1, fixture.sent.size)
        binding.render(fixture.current)
        assertTrue(items[0].isSelected)
        assertFalse(items[1].isSelected)
        fixture.current = connected(AppleListeningMode.TRANSPARENCY, AppleListeningMode.entries.toSet())
        binding.render(fixture.current)
        items[3].performClick()
        assertEquals(AppleControlRequest.SetListeningMode(AppleListeningMode.ADAPTIVE), fixture.sent.last())
        assertTrue(items[0].isSelected)
    }

    @Test
    fun staleAndMissingEvidenceCannotSendFromPreviouslyEnabledViews() {
        val connected = connected()
        val blocked = listOf(
            connected.copy(lifecycle = connected.lifecycle.copy(systemProfile = SystemProfileState.DISCONNECTED)),
            connected.copy(lifecycle = connected.lifecycle.copy(privateTransport = PrivateTransportState.IDLE)),
            connected.copy(lifecycle = connected.lifecycle.copy(protocolHandshake = ProtocolHandshakeState.PENDING)),
            connected.copy(lifecycle = connected.lifecycle.copy(
                controlOwnership = ControlOwnership.EXTERNAL_APP,
                externalControlApp = ControlAppSpec("test.controller", "Test controller"),
            )),
            connected.copy(features = DeviceFeatureSnapshot()),
        )
        blocked.forEach { state ->
            val fixture = Fixture()
            val binding = fixture.bind()
            binding.render(fixture.current)
            fixture.current = state
            fixture.items().forEach { it.performClick() }
            assertTrue(fixture.sent.isEmpty())
            binding.render(state)
            assertTrue(fixture.items().none { it.isEnabled })
        }
    }

    @Test
    fun supportedModesGateEachChoiceAndAdaptiveDisappearsWhenEvidenceIsRemoved() {
        val fixture = Fixture()
        val binding = fixture.bind()
        fixture.current = connected(AppleListeningMode.ADAPTIVE, AppleListeningMode.entries.toSet())
        binding.render(fixture.current)
        fixture.current = connected(AppleListeningMode.ANC, setOf(AppleListeningMode.ANC))
        fixture.items()[3].performClick()
        fixture.items()[0].performClick()
        assertTrue(fixture.sent.isEmpty())
        binding.render(fixture.current)
        assertEquals(listOf(false, true, false, false), fixture.items().map { it.isEnabled })
        assertEquals(View.GONE, fixture.items()[3].visibility)
        assertFalse(fixture.items()[3].isSelected)
    }

    @Test
    fun carrierProjectionUsesOnlyConfirmedListeningFeature() {
        val adapter = requireNotNull(MiLinkCardAdapterRegistry.resolve(AppleMiLinkPresentationIds.LISTENING))
        assertEquals(MiLinkNativeCardSurface.ANC_THREE_STATE, adapter.nativeSurface)
        assertNull(adapter.nativeSurfaceNoiseMode(EarbudState()))
        assertEquals(NoiseMode.OFF, adapter.nativeSurfaceNoiseMode(connected(AppleListeningMode.OFF)))
        assertEquals(NoiseMode.TRANSPARENCY, adapter.nativeSurfaceNoiseMode(connected(AppleListeningMode.TRANSPARENCY)))
        assertEquals(NoiseMode.ANC, adapter.nativeSurfaceNoiseMode(connected(AppleListeningMode.ANC)))
        assertEquals(NoiseMode.ANC, adapter.nativeSurfaceNoiseMode(connected(AppleListeningMode.ADAPTIVE)))
    }

    @Test
    fun itemConstructionFailureLeavesHostUntouched() {
        NativeAncModeLayout.entries.forEach { layout ->
            val fixture = Fixture(layout)
            // The second source has an incompatible shape, after the first replacement was built.
            val source = fixture.originals[1]
            source.removeView(source.findMiLinkView(layout.itemIconId))
            assertNull(fixture.tryBind())
            assertEquals(fixture.originals, fixture.items())
            assertEquals("Original title", fixture.title.text.toString())
        }
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
        val sent = mutableListOf<ControlRequest>()
        init {
            container.id = ids.getValue(layout.containerId)
            root.addView(title)
            root.addView(container)
            originals.forEach(container::addView)
        }
        fun bind(): MiLinkCardBinding = requireNotNull(tryBind())
        fun tryBind(): MiLinkCardBinding? =
            requireNotNull(MiLinkCardAdapterRegistry.resolve(AppleMiLinkPresentationIds.LISTENING)).bind(
                root, "00:11:22:33:44:55", MiLinkCardEnvironment(
                    javaClass.classLoader!!, { current }, { address, request ->
                        assertEquals("00:11:22:33:44:55", address)
                        sent += request
                    }, { _, _ -> {} },
                ),
            )
        fun items(): List<View> = (0 until container.childCount).map(container::getChildAt)
    }

    companion object {
        private fun connected(
            mode: AppleListeningMode = AppleListeningMode.ANC,
            supported: Set<AppleListeningMode> = setOf(AppleListeningMode.TRANSPARENCY, AppleListeningMode.ANC, AppleListeningMode.OFF),
        ) = EarbudState(lifecycle = DeviceLifecycle(
            systemProfile = SystemProfileState.CONNECTED,
            privateTransport = PrivateTransportState.CONNECTED,
            protocolHandshake = ProtocolHandshakeState.CONFIRMED,
        )).withFeature(AppleListeningModeFeatureState(mode, supported))
    }
}

