package dev.hyperears.hook

import android.view.View
import dev.hyperears.integration.*

/** Reuses model-owned eligibility and editors; no parallel feature schema or model-name guessing. */
internal object SettingsAdvancedEditors {
    data class Editor(val available: (EarbudState, String) -> Boolean,
        val open: (View, String, MiLinkCardEnvironment) -> MiLinkToggleDetails)
    private val editors = listOf(
        Editor(AppleSettingsCardPolicy::available, AppleSettingsDetails::open),
        Editor(SonyCardPolicy::available, SonySettingsDetails::open),
        Editor(OppoCardPolicy::available, OppoSettingsDetails::open),
        Editor(BoseDailyCardPolicy::available, BoseDailySettingsDetails::open),
        Editor(PuddingCardPolicy::available, PuddingSettingsDetails::open),
        Editor(RoseLegacySettingsPolicy::available, RoseLegacySettingsDetails::open),
        Editor(RoseZtSettingsPolicy::available, RoseZtSettingsDetails::open),
        Editor(RoseRsV2SettingsPolicy::available, RoseRsV2SettingsDetails::open),
        Editor(RoseCeramicsXSettingsPolicy::available, RoseCeramicsXSettingsDetails::open),
        Editor(QcyCardPolicy::available, QcySettingsDetails::open),
        Editor(VivoTws3eSettingsPolicy::available, VivoTws3eSettingsDetails::open),
        Editor(HuaweiDailySettingsPolicy::available, HuaweiDailySettingsDetails::open),
        Editor(SamsungDailySettingsPolicy::available, SamsungDailySettingsDetails::open),
        Editor(NiceHckCardPolicy::available, NiceHckSettingsDetails::open),
        Editor(RoseCoralCardPolicy::available, RoseCoralSettingsDetails::open),
        Editor(RoseRfcommCardPolicy::available, RoseRfcommSettingsDetails::open),
        Editor(TechnicsCardPolicy::available, TechnicsSettingsDetails::open),
    )
    fun forState(state: EarbudState): Editor? {
        val address = state.address ?: return null
        return editors.firstOrNull { it.available(state, address) }
    }
}
