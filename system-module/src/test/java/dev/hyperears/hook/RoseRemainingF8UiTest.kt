package dev.hyperears.hook

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import dev.hyperears.integration.*
import dev.hyperears.protocol.rose.RoseLegacyIdentityCodec.Model
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class RoseRemainingF8UiTest {
    @Test fun agateCustomLabelsUseReportedWireValuesAndOldOwnerCannotWrite() {
        for ((model, labels) in listOf(Model.AGATE to listOf("高保真", "摇滚"), Model.AGATE_X to listOf("流行", "摇滚"))) {
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val anchor = View(activity).also(activity::setContentView)
            val adapter = RoseLegacyAdapter(model)
            adapter.receive(byteArrayOf(0, 0x27, 2, 0, 4, 4, 2, 0, 0))
            val address = "02:00:00:00:00:11"
            var state = EarbudState(adapter = adapter.snapshot(), address = address, features = adapter.runtimeState().features,
                lifecycle = DeviceLifecycle(SystemProfileState.CONNECTED, PrivateTransportState.CONNECTED, ProtocolHandshakeState.CONFIRMED))
            val sent = mutableListOf<ControlRequest>()
            val env = MiLinkCardEnvironment(javaClass.classLoader!!, { state }, { _, req -> sent += req }, { _, _ -> {} }, { "one" })
            val details = RoseLegacySettingsDetails.open(anchor, address, env)
            shadowOf(Looper.getMainLooper()).idle()
            val popup = details.javaClass.getDeclaredField("popup").apply { isAccessible = true }.get(details) as PopupWindow
            val choices = descendants(popup.contentView).filterIsInstance<RadioButton>().toList()
            assertEquals(labels, choices.map { it.text.toString() })
            choices[1].performClick()
            assertEquals(listOf(RoseLegacyControlRequest.SetValue(model.name, RoseLegacySetting.EQ, 1)), sent)
            assertEquals(0, state.features.get<RoseLegacySettingsFeatureState>()!!.values[RoseLegacySetting.EQ])
            adapter.receive(byteArrayOf(0, 0x27, 2, 0, 4, 4, 2, 0, 2))
            state = state.copy(features = adapter.runtimeState().features)
            details.render(state)
            assertTrue(descendants(popup.contentView).filterIsInstance<RadioButton>().single { it.text == "摇滚" }.isChecked)
            state = state.copy(lifecycle = state.lifecycle.copy(controlOwnership = ControlOwnership.EXTERNAL_APP, externalControlApp = ControlAppCatalog.roseLink))
            choices[1].performClick(); assertEquals(1, sent.size); assertFalse(popup.isShowing)
            details.dismiss(); activity.finish()
        }
    }
    @Test fun variantDepthPolicyRequiresCurrentModeAndIndependentEvidence() {
        val adapter = RoseLegacyAdapter(Model.CERAMICS_X_7034)
        adapter.receive(byteArrayOf(0, 0x27, 2, 0, 3, 17, 1, 0))
        val address = "02:00:00:00:00:11"
        var state = EarbudState(adapter = adapter.snapshot(), address = address, features = adapter.runtimeState().features,
            lifecycle = DeviceLifecycle(SystemProfileState.CONNECTED, PrivateTransportState.CONNECTED, ProtocolHandshakeState.CONFIRMED))
        val request = RoseLegacyControlRequest.SetValue(Model.CERAMICS_X_7034.name, RoseLegacySetting.ANC_LEVEL, 2)
        assertNull(RoseLegacySettingsPolicy.request(state, address, request))
        state = state.withFeature(NoiseModeFeatureState(NoiseMode.ANC))
        assertEquals(request, RoseLegacySettingsPolicy.request(state, address, request))
        assertEquals(listOf("高", "中", "低"), RoseLegacySettingsPolicy.labels(RoseLegacySetting.ANC_LEVEL,
            state.features.get<RoseLegacySettingsFeatureState>()!!))
        assertNull(RoseLegacySettingsPolicy.request(state, address, request.copy(expectedModel = "EXPLORER")))
    }
    private fun descendants(v: View): Sequence<View> = sequence { yield(v); if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(descendants(v.getChildAt(i))) }
}
