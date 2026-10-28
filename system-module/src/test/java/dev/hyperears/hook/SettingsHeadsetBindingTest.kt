package dev.hyperears.hook

import android.app.Activity
import android.os.Binder
import android.view.View
import androidx.preference.*
import dev.hyperears.integration.*
import dev.hyperears.presentation.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real AndroidX preference objects exercise Settings' documented public preference contract. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SettingsHeadsetBindingTest {
    @Test fun nativeTextDraftWaitsForDeviceReportAndRejectsInvalidInput() {
        val f = Fixture()
        val sent = mutableListOf<String>()
        val host = SettingsPreferenceHost(javaClass.classLoader!!, f.screen.context, f.screen)
        val row = host.textInput("text-test", "名称", "耳机", true,
            { it.isNotBlank() && it.length <= 8 }, "名称无效", sent::add) as EditTextPreference
        assertFalse(row.isPersistent)
        assertFalse(row.callChangeListener("新名称"))
        assertEquals(listOf("新名称"), sent)
        assertEquals("耳机", row.text)
        assertEquals("耳机", row.summary.toString())
        assertFalse(row.callChangeListener(""))
        assertEquals(1, sent.size)
        host.clearListeners(row)
        row.callChangeListener("旧回调")
        assertEquals(1, sent.size)
        f.binding.close()
    }
    private val address = "00:11:22:33:44:55"
    private fun state() = EarbudState(address = address, adapter = HonorX5sProAdapter().snapshot().copy(
        presentationId = HonorX5sProAdapter.PRESENTATION_ID,
        supportedNoiseModes = setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY)),
        lifecycle = DeviceLifecycle(systemProfile = SystemProfileState.CONNECTED, privateTransport = PrivateTransportState.CONNECTED,
            protocolHandshake = ProtocolHandshakeState.CONFIRMED))
        .withFeature(NoiseModeFeatureState(NoiseMode.ANC))
        .withFeature(HonorAncDepthFeatureState(HonorAncDepth.SMART, HonorAncDepth.entries.toSet()))
    @Test fun explicitCurrentSummaryReplacesThemeProviderAndWaitsForReports() {
        val f=Fixture()
        val host=SettingsPreferenceHost(javaClass.classLoader!!, f.screen.context, f.screen)
        val row=host.choice("summary-test","选择",listOf("低","高"),listOf("1","5"),"1",true) {} as ListPreference
        assertEquals("低",row.summary.toString())
        row.callChangeListener("5")
        assertEquals("低",row.summary.toString())
        row.summaryProvider=ListPreference.SimpleSummaryProvider.getInstance()
        host.summary(row,"当前：无功能（耳机回报）")
        assertEquals("当前：无功能（耳机回报）",row.summary.toString())
        f.binding.close()
    }

    @Test fun ceramicsXNativeAudioWaitsForConfirmationAndDoesNotChangeReportedValue() {
        val f = Fixture()
        val adapter = RoseLuliXAdapter()
        adapter.receive(byteArrayOf(0, 0x28, 2, 0, 3, 11, 1, 0))
        f.current = f.current.copy(adapter = adapter.snapshot(), features = adapter.runtimeState().features)
        f.binding.render()
        val row = f.row("hyperears.ceramicsx.audio") as ListPreference
        assertFalse(row.callChangeListener("1"))
        assertTrue(f.sent.isEmpty())
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(RoseCeramicsXControlRequest.SetValue(RoseCeramicsXSetting.AUDIO, 1), f.sent.single())
        assertEquals("0", (f.row("hyperears.ceramicsx.audio") as ListPreference).value)
        f.binding.close()
    }


    @Test fun detectedSonyOptimizerAnnotatesOneExistingHandoffRowAndRevokesOnNewReport() {
        val f=Fixture()
        f.current=f.current.copy(adapter=f.current.adapter!!.copy(controlApps=listOf(ControlAppCatalog.sonySoundConnect)))
            .withFeature(SonyVendorToolsFeatureState(1,true))
        f.binding.render()
        val row=f.row("hyperears.controller.open")!!
        assertEquals("降噪优化 · 打开 Sony App 并让出控制",row.title.toString())
        assertNull(f.row("hyperears.optimizer"));assertTrue(f.sent.isEmpty())
        val old=row.onPreferenceClickListener!!
        f.current=f.current.withFeature(SonyVendorToolsFeatureState(1,false))
        old.onPreferenceClick(row);assertTrue(f.sent.isEmpty())
        f.binding.render()
        assertEquals("打开控制 App 并让出控制",f.row("hyperears.controller.open")!!.title.toString())
        f.binding.close()
    }

    @Test fun controllerRowsRequireModuleForLaunchAndExplicitLocalLeaseForReturn() {
        val f = Fixture(); f.current = f.current.copy(adapter = f.current.adapter!!.copy(controlApps = listOf(ControlAppCatalog.huaweiSmartAudio)))
        f.binding.render()
        assertNotNull(f.row("hyperears.controller.open"))
        f.current = f.current.copy(lifecycle = DeviceLifecycle(SystemProfileState.CONNECTED,
            PrivateTransportState.NOT_REQUIRED, ProtocolHandshakeState.NOT_REQUIRED,
            ControlOwnership.EXTERNAL_APP, ControlAppCatalog.huaweiSmartAudio))
        f.binding.render()
        assertNull(f.row("hyperears.headset"))
        f.returnAllowed = true; f.binding.render()
        assertNotNull(f.row("hyperears.controller.return"))
        assertNull(f.row("hyperears.noise")); assertNull(f.row("hyperears.honor.depth"))
        assertNull(f.row("hyperears.controller.open"))
        f.binding.close(); assertEquals(1, f.screen.preferenceCount)
    }

    @Test fun nativeNoiseAndDepthWaitForReadbackAndLeaveStockPreferencesUntouched() {
        val f = Fixture(); f.binding.render()
        val noise = f.row("hyperears.noise") as ListPreference
        assertFalse(noise.isPersistent)
        assertFalse(noise.callChangeListener("OFF"))
        assertEquals("ANC", noise.value)
        assertEquals("降噪", noise.summary.toString())
        assertEquals(StandardControlRequest.SetNoiseMode(NoiseMode.OFF), f.sent.single())
        val depth = f.row("hyperears.honor.depth") as ListPreference
        assertFalse(depth.callChangeListener("DEEP"))
        assertEquals("SMART", depth.value)
        assertEquals(HonorControlRequest.SetAncDepth(HonorAncDepth.DEEP), f.sent.last())
        f.current = f.current.withFeature(HonorAncDepthFeatureState(HonorAncDepth.DEEP, HonorAncDepth.entries.toSet()))
        f.binding.render()
        assertEquals("DEEP", (f.row("hyperears.honor.depth") as ListPreference).value)
        assertSame(f.screen, f.stock.parent)
        f.binding.close(); assertEquals(1, f.screen.preferenceCount)
        assertNull(depth.onPreferenceChangeListener)
    }
    @Test fun providerOffOrReplacedSessionBlocksOldDialogClicks() {
        for (mutate in listOf<(Fixture) -> Unit>(
            { it.current = it.current.withFeature(NoiseModeFeatureState(NoiseMode.OFF)) },
            { it.token = "next" }, { it.pageAddress = "another" }, { it.admitted = false }, { it.failProvider = true },
        )) {
            val f = Fixture(); f.binding.render()
            val depth = f.row("hyperears.honor.depth") as ListPreference
            mutate(f); assertFalse(depth.callChangeListener("DEEP")); assertTrue(f.sent.isEmpty())
            f.binding.render()
            if (f.current.noiseMode == NoiseMode.OFF && f.admitted && !f.failProvider && f.pageAddress == address && f.token == "token")
                assertFalse(f.row("hyperears.honor.depth")!!.isEnabled)
            f.binding.close()
        }
    }
    @Test fun reconstructedHostCanMoveCategoryAndCleanupRemovesItFromActualParent() {
        val f = Fixture(); f.binding.render()
        val original = f.row("hyperears.headset")!!
        val hidden = PreferenceCategory(f.activity).apply { key = "stock.hidden" }
        f.screen.addPreference(hidden); f.screen.removePreference(original); hidden.addPreference(original)
        f.binding.reconcile()
        assertEquals(0, hidden.preferenceCount)
        assertSame(f.screen, f.row("hyperears.headset")?.parent)
        assertEquals(1, (0 until f.screen.preferenceCount).count { f.screen.getPreference(it).key == "hyperears.headset" })
        f.admitted = false; f.binding.render()
        assertNull(f.row("hyperears.headset")); assertSame(f.screen, f.stock.parent)
    }
    @Test fun appleAdaptiveRemainsDistinctAndUsesTypedListeningControl() {
        val f = Fixture()
        f.current = f.current.withFeature(AppleListeningModeFeatureState(AppleListeningMode.ADAPTIVE, AppleListeningMode.entries.toSet()))
        f.binding.render()
        assertNull(f.row("hyperears.noise"))
        val row = f.row("hyperears.apple.listening") as ListPreference
        assertEquals(4, row.entries.size); assertEquals("ADAPTIVE", row.value)
        assertFalse(row.callChangeListener("TRANSPARENCY"))
        assertEquals(AppleControlRequest.SetListeningMode(AppleListeningMode.TRANSPARENCY), f.sent.single())
    }
    @Test fun moduleBatteryZeroShownWithoutInventingUnavailableOrAggregateComponents() {
        val f = Fixture()
        f.current = f.current.withFeature(BatteryFeatureState(EarbudBattery.fromAggregate(0)))
        f.binding.render()
        assertEquals("0%", f.row("hyperears.battery.0")?.summary)
        assertNull(f.row("hyperears.battery.1"))
    }
    @Test fun inlineAppleUsesReportedValuesAndRevokesStaleFields() {
        val f = Fixture()
        f.current = f.current.withFeature(AppleSettingsFeatureState(oneBudAnc = false, microphone = AppleMicrophone.entries[0]))
        f.binding.render()
        val toggle = f.row("hyperears.apple.one_bud_anc") as SwitchPreferenceCompat
        assertFalse(toggle.callChangeListener(true)); assertFalse(toggle.isChecked)
        assertEquals(AppleControlRequest.SetOneBudAnc(true), f.sent.single())
        val stale = f.row("hyperears.apple.microphone") as ListPreference
        f.current = f.current.withFeature(AppleSettingsFeatureState(oneBudAnc = false))
        stale.callChangeListener("1"); assertEquals(1, f.sent.size)
        f.binding.close(); assertNull(stale.onPreferenceChangeListener)
    }
    @Test fun coralAudioRequiresActualConfirmationAndFreshSession() {
        val f = Fixture()
        f.current = f.current.withFeature(RoseCoralAudioFeatureState(RoseCoralAudioMode.AAC_SBC))
            .withFeature(RoseCoralSettingsFeatureState(gameMode = false, bassLevel = 0))
        f.binding.render()
        (f.row("hyperears.coral.game") as SwitchPreferenceCompat).callChangeListener(true)
        assertEquals(RoseCoralControlRequest.SetGameMode(true), f.sent.single())
        (f.row("hyperears.coral.audio") as ListPreference).callChangeListener("LDAC")
        assertEquals(1, f.sent.size)
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(RoseCoralControlRequest.SetAudioMode(RoseCoralAudioMode.LDAC), f.sent.last())
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(2, f.sent.size)
        (f.row("hyperears.coral.audio") as ListPreference).callChangeListener("LHDC")
        val stale = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        f.token = "next"
        stale.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(2, f.sent.size)
        f.binding.render()
        (f.row("hyperears.coral.audio") as ListPreference).callChangeListener("LDAC")
        val ownerDialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        f.current = f.current.copy(lifecycle = f.current.lifecycle.copy(controlOwnership = ControlOwnership.EXTERNAL_APP,
            externalControlApp = ControlAppCatalog.roseLink))
        f.binding.render()
        assertFalse(ownerDialog.isShowing)
        ownerDialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(2, f.sent.size)
        f.binding.close()
    }
    @Test fun vendorRowsPreserveNiceHckFirmwareAndOppoDefinitionGates() {
        val f = Fixture()
        f.current = f.current.withFeature(NiceHckSettingsFeatureState(mapOf(NiceHckSetting.EQ to 0), 1, 8))
        f.binding.render()
        val eq = f.row("hyperears.nicehck.EQ") as ListPreference
        assertEquals(7, eq.entries.size)
        f.current = f.current.withFeature(NiceHckSettingsFeatureState(mapOf(NiceHckSetting.EQ to 0), 1, 7))
        eq.callChangeListener("6"); assertTrue(f.sent.isEmpty())
        f.binding.render()
        assertEquals(5, (f.row("hyperears.nicehck.EQ") as ListPreference).entries.size)
        val definition = OppoNoiseDefinition(0x065414, listOf(OppoNoiseOption(OppoNoiseMode.OFF,0,true), OppoNoiseOption(OppoNoiseMode.DEEP,4,true)))
        f.current = f.current.withFeature(OppoSettingsFeatureState(noise = OppoNoiseSelection(definition,0)))
        f.binding.render()
        val noise = f.row("hyperears.oppo.noise") as ListPreference
        noise.callChangeListener("4")
        assertEquals(OppoControlRequest.SelectNoiseOption(definition,4),f.sent.single())
        val stale = f.row("hyperears.oppo.noise") as ListPreference
        f.current = f.current.withFeature(OppoSettingsFeatureState(noise = OppoNoiseSelection(definition.copy(productId = 1),0)))
        stale.callChangeListener("4"); assertEquals(1,f.sent.size)
        f.binding.close()
    }
    @Test fun puddingAndBoseRowsUseReportedFieldsAndSourceValues() {
        val f = Fixture()
        f.current = f.current.withFeature(PuddingSettingsFeatureState(mapOf(PuddingSetting.GAIN to 2)))
            .withFeature(BoseDailySettingsFeatureState(sidetone = 0, autoAnswer = false))
        f.binding.render()
        (f.row("hyperears.pudding.GAIN") as ListPreference).callChangeListener("0")
        assertEquals(PuddingControlRequest.SetValue(PuddingSetting.GAIN,0), f.sent.last())
        (f.row("hyperears.bose.sidetone") as ListPreference).callChangeListener("2")
        assertEquals(BoseDailyControlRequest.SetSidetone(2),f.sent.last())
        val answer = f.row("hyperears.bose.autoAnswer") as SwitchPreferenceCompat
        answer.callChangeListener(true)
        assertFalse(answer.isChecked)
        assertEquals(BoseDailyControlRequest.SetAutoAnswer(true),f.sent.last())
        f.binding.close()
    }
    @Test fun appleNativeRangeKeepsConfirmedValueAndRejectsOutOfRange() {
        val f = Fixture()
        f.current = f.current.withFeature(AppleSettingsFeatureState(chimeVolume = 30))
        f.binding.render()
        val range = f.row("hyperears.apple.chime_volume") as SeekBarPreference
        assertEquals(15,range.min); assertEquals(100,range.max)
        range.callChangeListener(45)
        assertEquals(30,range.value)
        assertEquals(AppleControlRequest.SetChimeVolume(45),f.sent.single())
        (f.row("hyperears.apple.chime_volume") as SeekBarPreference).callChangeListener(101)
        assertEquals(1,f.sent.size)
        f.binding.close()
    }
    @Test fun reselectingCurrentAncNeverOverwritesBoseReportedIntensity() {
        val f = Fixture()
        f.current = f.current.withFeature(BoseDailySettingsFeatureState(anr = 3, cnc = BoseCncLevel(5, true)))
        f.binding.render()
        val row = f.row("hyperears.noise") as ListPreference
        assertFalse(row.callChangeListener("ANC"))
        assertTrue(f.sent.isEmpty())
        assertEquals(5, f.current.features.get<BoseDailySettingsFeatureState>()!!.cnc!!.rawLevel)
        f.binding.close()
    }
    @Test fun addedOppoAndHuaweiRowsRetainCurrentDefinitionAndLevelDomain() {
        val f = Fixture()
        f.current = f.current.withFeature(OppoSettingsFeatureState(promptVolume=3,spatialSound=false,spatialProductId=7))
            .withFeature(HuaweiAncLevelFeatureState(HuaweiAncLevel.NORMAL,HuaweiAncLevel.entries.toSet()))
        f.binding.render()
        (f.row("hyperears.oppo.prompt") as SeekBarPreference).callChangeListener(5)
        assertEquals(OppoControlRequest.SetPromptVolume(5),f.sent.last())
        val spatial=f.row("hyperears.oppo.spatial") as SwitchPreferenceCompat
        f.current=f.current.withFeature(OppoSettingsFeatureState(promptVolume=3,spatialSound=false,spatialProductId=8))
        spatial.callChangeListener(true); assertEquals(1,f.sent.size)
        val level=f.row("hyperears.huawei.level") as ListPreference
        f.current=f.current.withFeature(NoiseModeFeatureState(NoiseMode.TRANSPARENCY))
        level.callChangeListener(HuaweiAncLevel.ULTRA.ordinal.toString()); assertEquals(1,f.sent.size)
        f.binding.close()
    }
    @Test fun roseNativeAudioNeedsConfirmationAndSameProfile() {
        val f = Fixture()
        val model=dev.hyperears.protocol.rose.RoseRsV2IdentityCodec.Model.ASTRO_X
        f.current=f.current.copy(adapter=f.current.adapter!!.copy(presentationId=RoseRsV2PresentationIds.SETTINGS))
            .withFeature(RoseRsV2SettingsFeatureState(model.name,eq=0,audio=0,game=false))
        f.binding.render()
        (f.row("hyperears.rsv2.audio") as ListPreference).callChangeListener("1")
        assertTrue(f.sent.isEmpty())
        val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(RoseRsV2ControlRequest.SetAudio(1),f.sent.single())
        (f.row("hyperears.rsv2.eq") as ListPreference).callChangeListener("2")
        assertEquals(RoseRsV2ControlRequest.SetEqualizer(2),f.sent.last())
        val stale=f.row("hyperears.rsv2.eq") as ListPreference
        f.current=f.current.withFeature(RoseRsV2SettingsFeatureState("OPENFEEL_L",eq=0))
        stale.callChangeListener("1"); assertEquals(2,f.sent.size)
        f.binding.close()
    }
    @Test fun appleIdentityRowsUseRemoteReportWithoutGrantingControlsOrUsingAlias() {
        val f=Fixture()
        f.current=f.current.withFeature(AppleDeviceInformationFeatureState("Reported name","A3441","9A1"))
        f.binding.render()
        assertEquals("Reported name",f.row("hyperears.apple.info.name")!!.summary)
        assertEquals("AirPods 5 Wireless",f.row("hyperears.apple.info.model")!!.summary)
        assertFalse(f.row("hyperears.apple.info.name")!!.isEnabled)
        assertTrue(f.sent.isEmpty())
        f.binding.close()
    }
    @Test fun legacyExplorerAudioRequiresConfirmationAndDifferentProfileCannotReuseChoice() {
        val f=Fixture()
        val model=dev.hyperears.protocol.rose.RoseLegacyIdentityCodec.Model.EXPLORER
        f.current=f.current.copy(adapter=RoseLegacyAdapter(model).snapshot().copy(presentationId=RoseLegacyAdapter.SETTINGS_ONLY))
            .withFeature(RoseLegacySettingsFeatureState(model.name,mapOf(RoseLegacySetting.AUDIO to 2,RoseLegacySetting.GAME to 0)))
        f.binding.render()
        val audio=f.row("hyperears.legacy.audio") as ListPreference
        audio.callChangeListener("1")
        assertTrue(f.sent.isEmpty())
        val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(RoseLegacyControlRequest.SetValue(model.name,RoseLegacySetting.AUDIO,1),f.sent.single())
        f.binding.close()
    }
    private inner class Fixture {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val anchor = View(activity).also(activity::setContentView)
        val screen = PreferenceManager(activity).createPreferenceScreen(activity)
        val stock = Preference(activity).apply { key = "stock" }.also(screen::addPreference)
        var returnAllowed = false
        var current = state(); var token = "token"; var pageAddress = address; var admitted = true; var failProvider = false
        val sent = mutableListOf<ControlRequest>()
        val env = MiLinkCardEnvironment(javaClass.classLoader!!, { current }, { _, request -> sent += request }, { _, _ -> {} }, { token })
        val host = SettingsPreferenceHost(javaClass.classLoader!!, activity, screen)
        val binding = SettingsHeadsetBinding(host, anchor, address, { pageAddress }, {
            if (failProvider) error("provider")
            if (!admitted) null else SettingsHeadsetState.Record(current, token,
                NativePresentationMetadata(HeadsetConnection("connection", 1), NativeAdmission.HYPEREARS), Binder())
        }, env, canReturn = { returnAllowed })
        fun row(key: String): Preference? = screen.findPreference(key)
    }
}
