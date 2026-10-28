package dev.hyperears.hook

import android.view.View
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.NiceHckYuanDaoOrigAdapter

/** Preserve the existing native three-state/wind accessory, then add the model-scoped details entry. */
internal object NiceHckMiLinkCardAdapter : MiLinkCardAdapter {
    override val presentationId = NiceHckYuanDaoOrigAdapter.ADVANCED_PRESENTATION_ID
    override fun bind(root: View, address: String, environment: MiLinkCardEnvironment): MiLinkCardBinding? {
        val row = NativeAncModeRow.resolve(root) ?: return null
        val base = NiceHckOrigMiLinkCardAdapter.bind(root, address, environment) ?: return null
        val entry = try {
            // The existing title already hosts the wind switch. Attach above the card so its
            // title accessory and the advanced entry never compete for the same horizontal space.
            AppleSettingsCardEntry.bind(null, row.container, address, environment,
                NiceHckCardPolicy::available, NiceHckSettingsDetails::open)
        } catch (_: Exception) { base.unbind(); return null }
        return object : MiLinkCardBinding {
            private var bound = true
            override fun render(state: EarbudState) {
                if (!bound) return
                try { base.render(state); entry?.render(state) } catch (_: Exception) { unbind() }
            }
            override fun unbind() {
                if (!bound) return
                bound = false
                try { entry?.unbind() } finally { base.unbind() }
            }
        }
    }
}
