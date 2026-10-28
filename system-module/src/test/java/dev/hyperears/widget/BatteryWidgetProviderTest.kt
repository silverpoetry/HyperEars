package dev.hyperears.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import dev.hyperears.R
import dev.hyperears.bridge.*
import dev.hyperears.integration.*
import dev.hyperears.presentation.*
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class BatteryWidgetProviderTest {
    private val context:Application get()=RuntimeEnvironment.getApplication()
    private val address="AA:BB:CC:DD:EE:01"
    private fun bind(id:Int=7) {
        shadowOf(AppWidgetManager.getInstance(context)).addBoundWidget(id,AppWidgetProviderInfo().apply {
            provider=BatteryWidgetProvider.component(context);initialLayout=R.layout.battery_widget_compact
        })
    }
    private fun record()=BatteryWidgetRecord(address,"Buds",EarbudBattery(left=BatteryReading(0,true)),HeadsetFormFactor.TWS,BatteryWidgetStatus.CONNECTED,100L,10,true)
    @Before fun before(){BatteryWidgetRuntime.release();context.getSharedPreferences("battery_widgets",0).edit().clear().commit()}
    @After fun after(){BatteryWidgetRuntime.release()}
    @Test fun actualRemoteViewsResizeAndAggregateProjectionDoNotInventTelemetry() {
        val narrow=BatteryWidgetRenderer.render(context,7,Bundle(),record(),null)
        assertEquals(R.layout.battery_widget_compact,narrow.layoutId)
        val view=narrow.apply(context,FrameLayout(context))
        assertTrue(view.findViewById<TextView>(R.id.widget_left).text.toString().contains("0%"))
        assertFalse(view.findViewById<TextView>(R.id.widget_right).text.toString().contains("0%"))
        val wide=BatteryWidgetRenderer.render(context,7,Bundle().apply{putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,300)},
            record().copy(battery=EarbudBattery(overall=BatteryReading(80,false))),null)
        assertEquals(R.layout.battery_widget_wide,wide.layoutId)
        val wideView=wide.apply(context,FrameLayout(context))
        assertEquals(View.GONE,wideView.findViewById<View>(R.id.widget_right).visibility)
        assertTrue(wideView.findViewById<TextView>(R.id.widget_left).text.toString().contains("80%"))
    }
    @Test fun shortLayoutKeepsTimestampAndOverallKeepsReportedCase() {
        val short=BatteryWidgetRenderer.render(context,7,Bundle().apply{putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,80)},record(),null).apply(context,FrameLayout(context))
        assertEquals(View.VISIBLE,short.findViewById<View>(R.id.widget_time).visibility)
        assertEquals(View.GONE,short.findViewById<View>(R.id.widget_configure).visibility)
        val mixed=record().copy(battery=EarbudBattery(overall=BatteryReading(80,false),case=BatteryReading(50,true)))
        val view=BatteryWidgetRenderer.render(context,7,Bundle(),mixed,null).apply(context,FrameLayout(context))
        assertEquals(View.VISIBLE,view.findViewById<View>(R.id.widget_case).visibility)
        assertTrue(view.findViewById<TextView>(R.id.widget_case).text.toString().contains("50%"))
    }
    @Test fun configurationIsPerWidgetAndRestoreMigratesOnlyItsChoice() {
        bind(7);bind(8)
        val prefs=BatteryWidgetPreferences(context);prefs.save(listOf(record()))
        prefs.select(8,"AA:BB:CC:DD:EE:02")
        val controller=Robolectric.buildActivity(BatteryWidgetConfigurationActivity::class.java,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,7)).create()
        val activity=controller.get()
        val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        dialog.listView.performItemClick(null,1,1)
        assertEquals(address,prefs.fixedAddress(7))
        assertEquals("AA:BB:CC:DD:EE:02",prefs.fixedAddress(8))
        assertEquals(android.app.Activity.RESULT_OK,shadowOf(activity).resultCode)
        controller.destroy()
        bind(9);BatteryWidgetProvider().onRestored(context,intArrayOf(7),intArrayOf(9))
        assertNull(prefs.fixedAddress(7));assertEquals(address,prefs.fixedAddress(9))
        val options=Bundle().apply{putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,300)}
        AppWidgetManager.getInstance(context).updateAppWidgetOptions(9,options)
        BatteryWidgetProvider().onAppWidgetOptionsChanged(context,AppWidgetManager.getInstance(context),9,options)
        assertNotNull(shadowOf(AppWidgetManager.getInstance(context)).getViewFor(9))
    }
    @Test fun restoredIdsMayStayEqualOrOverlapWithoutLosingSelections() {
        bind(7);bind(8)
        val prefs=BatteryWidgetPreferences(context)
        prefs.select(7,address);prefs.select(8,"AA:BB:CC:DD:EE:02")
        BatteryWidgetProvider().onRestored(context,intArrayOf(7,8),intArrayOf(8,7))
        assertEquals(address,prefs.fixedAddress(8))
        assertEquals("AA:BB:CC:DD:EE:02",prefs.fixedAddress(7))
        BatteryWidgetProvider().onRestored(context,intArrayOf(7),intArrayOf(7))
        assertEquals("AA:BB:CC:DD:EE:02",prefs.fixedAddress(7))
    }
    @Test fun widgetLifecycleEnablesOnlyExplicitReceiverAndRequestsReplay() {
        bind();BatteryWidgetProvider().onUpdate(context,AppWidgetManager.getInstance(context),intArrayOf(7))
        val component=ComponentName(context,WidgetStateReceiver::class.java)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED,context.packageManager.getComponentEnabledSetting(component))
        assertTrue(shadowOf(context).broadcastIntents.any{it.action==ModuleContract.ACTION_REQUEST_STATE})
        val prefs=BatteryWidgetPreferences(context);prefs.save(listOf(record()));prefs.select(7,address)
        assertFalse(prefs.records().single().verified)
        assertFalse(context.getSharedPreferences("battery_widgets",0).all.toString().contains("session_token"))
        BatteryWidgetProvider().onDeleted(context,intArrayOf(7));assertNull(prefs.fixedAddress(7))
        BatteryWidgetProvider().onDisabled(context)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,context.packageManager.getComponentEnabledSetting(component))
        assertTrue(prefs.records().isEmpty())
    }
    @Test fun authenticatedReducedDeliveryRejectsSpoofAndSystemClaimClearsPersistedValues() {
        bind();shadowOf(context.packageManager).setPackagesForUid(12345,ModuleContract.BLUETOOTH_PACKAGE)
        val state=EarbudState(address=address,deviceName="Buds",revision=1,lifecycle=DeviceLifecycle(systemProfile=SystemProfileState.CONNECTED))
            .withFeature(BatteryFeatureState(EarbudBattery(left=BatteryReading(77,false))))
        val producer=Binder();val metadata=NativePresentationMetadata(HeadsetConnection("one",10),NativeAdmission.HYPEREARS)
        val event=NativePresentationContract.attach(ModuleContract.stateChanged(state,"token",context.packageName),metadata,producer)
        val receiver=WidgetStateReceiver()
        event.putExtra("sender_package",ModuleContract.BLUETOOTH_PACKAGE)
        assertFalse(receiver.accept(context,event,"attacker",12345))
        assertFalse(receiver.accept(context,event,ModuleContract.BLUETOOTH_PACKAGE,999))
        assertTrue(receiver.accept(context,event,ModuleContract.BLUETOOTH_PACKAGE,12345))
        assertEquals(77,BatteryWidgetPreferences(context).records().single().battery.left.percent)
        val system=NativePresentationContract.attach(ModuleContract.stateChanged(state.copy(revision=2),"token",context.packageName),metadata.copy(admission=NativeAdmission.SYSTEM),producer)
        assertTrue(receiver.accept(context,system,ModuleContract.BLUETOOTH_PACKAGE,12345))
        assertFalse(BatteryWidgetPreferences(context).records().single().hasBattery)
    }
    @Test fun immutableDistinctButtonsRouteToCurrentSelectedDeviceAndRejectForeignIds() {
        bind();BatteryWidgetPreferences(context).save(listOf(record()));BatteryWidgetPreferences(context).select(7,address)
        val open=BatteryWidgetRenderer.pending(context,7,false);val configure=BatteryWidgetRenderer.pending(context,7,true)
        assertTrue(shadowOf(open).isImmutable);assertTrue(shadowOf(open).isActivity)
        assertNotEquals(open,configure);assertNotEquals(open,BatteryWidgetRenderer.pending(context,8,false))
        val activity=Robolectric.buildActivity(BatteryWidgetClickActivity::class.java,shadowOf(open).savedIntent).create().get()
        val intent=shadowOf(activity).nextStartedActivity
        assertEquals("com.android.settings.BLUETOOTH_DEVICE_DETAIL_SETTINGS",intent.action)
        assertEquals(address,intent.getStringExtra("device_address"))
        val invalid=Robolectric.buildActivity(BatteryWidgetClickActivity::class.java,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,99)).create().get()
        assertNull(shadowOf(invalid).nextStartedActivity);assertTrue(invalid.isFinishing)
    }
    @Test fun publisherEmitsOnlyBatteryFeaturesAndForcedReplayBypassesDedup() {
        val metadata=NativePresentationMetadata(HeadsetConnection("publisher",100),NativeAdmission.HYPEREARS)
        val producer=Binder()
        val state=EarbudState(address="AA:BB:CC:DD:EE:02",revision=1,lifecycle=DeviceLifecycle(systemProfile=SystemProfileState.CONNECTED))
            .withFeature(BatteryFeatureState(EarbudBattery(left=BatteryReading(50,false))))
            .withFeature(NoiseModeFeatureState(NoiseMode.ANC))
        val before=shadowOf(context).broadcastIntents.size
        BatteryWidgetPublisher.publish(context,state,"s",metadata,producer)
        for(i in 2L..100L)BatteryWidgetPublisher.publish(context,state.copy(revision=i),"s",metadata,producer)
        BatteryWidgetPublisher.publish(context,state.copy(revision=100),"s",metadata,producer,replay=true)
        val broadcasts=shadowOf(context).broadcastIntents.drop(before).filter{it.component?.className==WidgetStateReceiver::class.java.name}
        assertEquals(2,broadcasts.size)
        assertEquals(broadcasts[0].getLongExtra(BatteryWidgetPublisher.RECORDED_AT,0),broadcasts[1].getLongExtra(BatteryWidgetPublisher.RECORDED_AT,0))
        val decoded=with(ModuleContract){broadcasts[0].readState()}!!
        assertEquals(1,decoded.features.values.size);assertTrue(decoded.features.values.single() is BatteryFeatureState)
    }
}
