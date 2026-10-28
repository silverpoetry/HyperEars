package dev.hyperears.widget

import android.app.Application
import android.os.Binder
import dev.hyperears.bridge.*
import dev.hyperears.integration.*
import dev.hyperears.presentation.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BatteryWidgetBroadcastIntegrationTest {
    @Test fun productionBroadcasterCoalescesWidgetUpdatesAndOnlyModuleRepliesForceReplay() {
        val context = RuntimeEnvironment.getApplication()
        val producer = Binder()
        val metadata = NativePresentationMetadata(HeadsetConnection("broadcast-widget", 300), NativeAdmission.HYPEREARS)
        val state = EarbudState(address = "AA:BB:CC:DD:EE:09", revision = 1,
            lifecycle = DeviceLifecycle(systemProfile = SystemProfileState.CONNECTED))
            .withFeature(BatteryFeatureState(EarbudBattery(overall = BatteryReading(80, false))))
        fun deliveries() = shadowOf(context).broadcastIntents.filter {
            it.component?.className == WidgetStateReceiver::class.java.name
        }
        StateBroadcaster.publish(context, state, "widget-token", metadata, producer)
        assertEquals(1, deliveries().size)
        StateBroadcaster.publish(context, state.copy(revision = 2).withNoiseMode(NoiseMode.ANC),
            "widget-token", metadata, producer)
        assertEquals(1, deliveries().size)
        val other = ModuleContract.stateConsumerPackages.first { it != ModuleContract.MODULE_PACKAGE }
        StateBroadcaster.reply(context, other, state.copy(revision = 2), "widget-token", metadata, producer)
        assertEquals(1, deliveries().size)
        StateBroadcaster.reply(context, ModuleContract.MODULE_PACKAGE, state.copy(revision = 2),
            "widget-token", metadata, producer)
        assertEquals(2, deliveries().size)
        assertTrue(NativePresentationContract.metadata(deliveries().last())!!.replay)
        assertEquals(deliveries().first().getLongExtra(BatteryWidgetPublisher.RECORDED_AT, 0),
            deliveries().last().getLongExtra(BatteryWidgetPublisher.RECORDED_AT, 0))
    }
}
