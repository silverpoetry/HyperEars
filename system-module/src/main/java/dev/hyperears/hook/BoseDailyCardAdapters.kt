package dev.hyperears.hook

import android.view.View
import dev.hyperears.integration.*

/** Existing Bose slot ownership/projection is delegated unchanged; details occupy a separate row. */
internal object BoseDailyTwoModeMiLinkCardAdapter : MiLinkCardAdapter by BoseDailyWrapper(
    BoseDailyPresentationIds.TWO_MODE, BoseTwoModeMiLinkCardAdapter)
internal object BoseDailyWindOffMiLinkCardAdapter : MiLinkCardAdapter by BoseDailyWrapper(
    BoseDailyPresentationIds.WIND_OFF, BoseQuietComfortMiLinkCardAdapter)
internal object BoseDailyWindTransparencyMiLinkCardAdapter : MiLinkCardAdapter by BoseDailyWrapper(
    BoseDailyPresentationIds.WIND_TRANSPARENCY, BoseAnrMiLinkCardAdapter)

private class BoseDailyWrapper(override val presentationId: MiLinkCardPresentationId,
    private val original: MiLinkCardAdapter) : MiLinkCardAdapter {
    override val nativeSurface get() = original.nativeSurface
    override fun nativeSurfaceNoiseMode(state: EarbudState) = original.nativeSurfaceNoiseMode(state)
    override fun projectNativeNoiseMode(mode: NoiseMode?) = original.projectNativeNoiseMode(mode)
    override fun bind(root: View, address: String, environment: MiLinkCardEnvironment): MiLinkCardBinding? {
        val row = NativeAncModeRow.resolve(root) ?: return null
        val base = original.bind(root, address, environment) ?: return null
        val entry = try {
            AppleSettingsCardEntry.bind(null, row.container, address, environment,
                BoseDailyCardPolicy::available, BoseDailySettingsDetails::open)
        } catch (_: Exception) { base.unbind(); return null }
        return object : MiLinkCardBinding {
            private var bound = true
            override fun render(state: EarbudState) { if (bound) { base.render(state); entry?.render(state) } }
            override fun unbind() {
                if (!bound) return
                bound = false
                try { entry?.unbind() } finally { base.unbind() }
            }
        }
    }
}
