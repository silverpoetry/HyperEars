package dev.hyperears.hook

import android.app.Application
import android.content.ContextWrapper
import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import android.widget.*
import dev.hyperears.integration.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class NativeAncToggleLifecycleTest {
    @Test fun staleToggleRejectsOwnershipAndSessionChangesUntilFreshRenderAndUnbindRestoresStock() {
        val app = RuntimeEnvironment.getApplication()
        @Suppress("DEPRECATION")
        val resources = object : Resources(app.assets, app.resources.displayMetrics, app.resources.configuration) {
            override fun getIdentifier(name: String?, type: String?, pkg: String?) = when(name) {
                "anc_card_title" -> 100; "anc_card" -> 101; else -> 0
            }
        }
        val context = object : ContextWrapper(app) { override fun getResources() = resources }
        val root = LinearLayout(context)
        val title = TextView(context).apply { id = 100; text = "ANC" }
        val stock = Button(context).apply { id = 101 }
        var stockClicks = 0; stock.setOnClickListener { stockClicks++ }
        root.addView(title); root.addView(stock)
        val id = MiLinkCardPresentationId("lifecycle-test")
        val spec = object : MiLinkToggleSpec {
            override val label = "Setting"
            override fun render(state: EarbudState) = MiLinkToggleState(false, true)
            override fun request(state: EarbudState, checked: Boolean): ControlRequest = StandardControlRequest.Refresh
        }
        val adapter = requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(deviceName = "HUAWEI FreeBuds Pro 3", standardHeadset = true)))
        val address = "00:11:22:33:44:55"
        var state = EarbudState(address = address, adapter = adapter.snapshot().copy(presentationId = id), lifecycle = DeviceLifecycle(
            systemProfile = SystemProfileState.CONNECTED, privateTransport = PrivateTransportState.CONNECTED,
            protocolHandshake = ProtocolHandshakeState.CONFIRMED))
        var token = "one"; val sent = mutableListOf<ControlRequest>()
        val binding = requireNotNull(NativeAncToggleMiLinkCardAdapter(id, "test", listOf(spec)).bind(root, address,
            MiLinkCardEnvironment(javaClass.classLoader!!, { state }, { _, request -> sent += request }, { _, _ -> {} }, { token })))
        binding.render(state)
        val toggle = children(root).filterIsInstance<CompoundButton>().single()
        toggle.isChecked = true; assertEquals(1, sent.size)
        state = state.copy(lifecycle = state.lifecycle.copy(controlOwnership = ControlOwnership.EXTERNAL_APP, externalControlApp = ControlAppCatalog.huaweiAiLife))
        toggle.isChecked = true; assertEquals(1, sent.size); assertFalse(toggle.isEnabled)
        state = state.copy(lifecycle = state.lifecycle.copy(controlOwnership = ControlOwnership.MODULE, externalControlApp = null))
        toggle.isChecked = false; toggle.isChecked = true; assertEquals(1, sent.size)
        binding.render(state); toggle.isChecked = true; assertEquals(2, sent.size)
        token = "two"; toggle.isChecked = true; assertEquals(2, sent.size)
        binding.render(state); toggle.isChecked = true; assertEquals(3, sent.size)
        binding.unbind(); binding.render(state); toggle.isChecked = false; toggle.isChecked = true
        assertEquals(3, sent.size); assertFalse(toggle.isEnabled)
        assertSame(title, root.getChildAt(0)); assertSame(stock, root.getChildAt(1))
        stock.performClick(); assertEquals(1, stockClicks)
    }
    private fun children(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
}
