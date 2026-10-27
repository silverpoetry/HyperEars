package dev.hyperears.hook

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import dev.hyperears.bridge.ModuleContract
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.FloS2ControlRequest
import dev.hyperears.integration.FloS2MiLinkPresentationIds
import dev.hyperears.integration.FloS2SoundEffect
import dev.hyperears.integration.FloS2SoundEffectFeatureState
import dev.hyperears.integration.MiLinkCardPresentationId
import dev.hyperears.integration.NoiseMode
import java.lang.ref.WeakReference

/**
 * Presents the four-way FloS2 sound-effect selector through MiLink's complete stock ANC-card slot.
 *
 * The device has no noise control, so the adapter borrows the native three-state surface the same
 * way FitClip Ultra does and temporarily replaces its mode items with four instances of the same
 * native item class, labelled with the four EQ presets. The original title and items are restored
 * on unbind. No height override, host method, obfuscated field, or delayed layout mutation is used.
 */
internal object FloS2SoundEffectMiLinkCardAdapter : MiLinkCardAdapter {
    override val presentationId: MiLinkCardPresentationId = FloS2MiLinkPresentationIds.SOUND_EFFECT
    override val nativeSurface: MiLinkNativeCardSurface =
        MiLinkNativeCardSurface.ANC_THREE_STATE

    override fun nativeSurfaceNoiseMode(state: EarbudState): NoiseMode? =
        NoiseMode.ANC.takeIf {
            state.features.get<FloS2SoundEffectFeatureState>() != null
        }

    override fun bind(
        root: View,
        address: String,
        environment: MiLinkCardEnvironment,
    ): MiLinkCardBinding? {
        val host = resolveNativeHost(root)
            ?: return skipped(address, "native-anc-card-unavailable")
        val items = FloS2SoundEffect.entries.mapIndexed { index, effect ->
            val source = host.sources[index % host.sources.size]
            host.createItem(root, source, EFFECT_LABELS.getValue(effect), effectIcon(root.context, effect))
                ?: return skipped(address, "sound-effect-item-construction-failed")
        }

        val binding = Binding(host, items, address, environment)
        host.install(SOUND_EFFECT_LABEL, items)
        items.forEachIndexed { index, item ->
            item.setOnClickListener { binding.onEffectClicked(FloS2SoundEffect.entries[index]) }
        }
        ModuleLog.debug(
            COMPONENT,
            "bound FloS2 sound-effect card layout=${host.generation} " +
                "address=${maskBluetoothAddress(address)}",
        )
        return binding
    }

    private fun skipped(address: String, reason: String): MiLinkCardBinding? {
        ModuleLog.debug(
            COMPONENT,
            "skipped FloS2 sound-effect card reason=$reason " +
                "address=${maskBluetoothAddress(address)}",
        )
        return null
    }

    private class Binding(
        private val host: NativeAncHost,
        items: List<View>,
        private val address: String,
        private val environment: MiLinkCardEnvironment,
    ) : MiLinkCardBinding {
        private val items = items.map(::WeakReference)
        private var lastRendered: FloS2SoundEffectCardPolicy.CardState? = null

        override fun render(state: EarbudState) {
            val views = items.mapNotNull(WeakReference<View>::get)
            if (views.size != this.items.size) return
            val projected = FloS2SoundEffectCardPolicy.render(state)
            views.forEachIndexed { index, view ->
                val selected = FloS2SoundEffect.entries[index] == projected.effect
                view.setNativeChoiceEnabled(projected.enabled)
                view.setSelectedTree(selected)
            }
            if (lastRendered != projected) {
                lastRendered = projected
                ModuleLog.debug(
                    COMPONENT,
                    "rendered FloS2 sound-effect card enabled=${projected.enabled} " +
                        "effect=${projected.effect} address=${maskBluetoothAddress(address)}",
                )
            }
        }

        fun onEffectClicked(effect: FloS2SoundEffect) {
            val current = environment.stateProvider(address)
            val requested = FloS2SoundEffectCardPolicy.request(current, effect) ?: return
            environment.controlSender(
                address,
                FloS2ControlRequest.SetSoundEffect(requested),
            )
        }

        override fun unbind() {
            items.mapNotNull(WeakReference<View>::get).forEach { item ->
                item.setOnClickListener(null)
            }
            host.restore()
        }
    }

    private class NativeAncHost(
        title: TextView,
        row: NativeAncModeRow,
    ) {
        private data class OriginalItem(
            val view: View,
            val index: Int,
            val layoutParams: ViewGroup.LayoutParams,
            val visibility: Int,
        )

        private val layout = row.layout
        val generation: String = layout.logName
        val sources: List<View> = row.items

        private val title = WeakReference(title)
        private val container = WeakReference(row.container)
        private val originalTitle = title.text
        private val originals = row.items.map { item ->
            OriginalItem(
                view = item,
                index = row.container.indexOfChild(item),
                layoutParams = item.layoutParams,
                visibility = item.visibility,
            )
        }

        private val replacements = mutableListOf<WeakReference<View>>()

        fun createItem(root: View, source: View, label: String, icon: Drawable?): View? {
            val item = layout.createItem(root, source, label) ?: return null
            val drawable = icon ?: return item
            (item.findMiLinkView(layout.itemIconId) as? ImageView)?.setImageDrawable(drawable)
            return item
        }

        fun install(titleText: String, items: List<View>) {
            val title = title.get() ?: return
            val container = container.get() ?: return
            title.text = titleText
            originals.sortedByDescending(OriginalItem::index).forEach { original ->
                if (original.view.parent === container) container.removeView(original.view)
            }
            val insertion = originals.minOfOrNull(OriginalItem::index)
                ?.coerceIn(0, container.childCount)
                ?: container.childCount
            items.forEachIndexed { offset, item ->
                container.addView(item, insertion + offset)
                replacements += WeakReference(item)
            }
        }

        fun restore() {
            val title = title.get() ?: return
            val container = container.get() ?: return
            replacements.mapNotNull(WeakReference<View>::get).forEach { replacement ->
                if (replacement.parent === container) container.removeView(replacement)
            }
            originals.sortedBy(OriginalItem::index).forEach { original ->
                if (original.view.parent == null) {
                    original.view.layoutParams = original.layoutParams
                    original.view.visibility = original.visibility
                    container.addView(
                        original.view,
                        original.index.coerceIn(0, container.childCount),
                    )
                }
            }
            title.text = originalTitle
            replacements.clear()
        }
    }

    private fun resolveNativeHost(root: View): NativeAncHost? =
        listOf(NativeAncModeLayout.SELECT_CARD, NativeAncModeLayout.ORIGINAL).firstNotNullOfOrNull { layout ->
            val row = NativeAncModeRow.resolve(root, layout) ?: return@firstNotNullOfOrNull null
            val title = root.findMiLinkView(layout.titleId) as? TextView ?: return@firstNotNullOfOrNull null
            NativeAncHost(title, row)
        }

    /**
     * Loads a module-owned vector icon from the MiLink process. Falls back to the cloned host
     * icon when the module package cannot be resolved or the resource is missing.
     */
    private fun effectIcon(context: Context, effect: FloS2SoundEffect): Drawable? = runCatching {
        val moduleContext = moduleContext(context)
            ?: return@runCatching null
        val identifier = moduleContext.resources.getIdentifier(
            EFFECT_ICON_NAMES.getValue(effect),
            "drawable",
            ModuleContract.MODULE_PACKAGE,
        ).takeIf { it != 0 } ?: return@runCatching null
        moduleContext.resources.getDrawable(identifier, null)
    }.getOrElse {
        ModuleLog.warn(COMPONENT, "FloS2 effect icon unavailable; using the host icon", it)
        null
    }

    @Volatile
    private var cachedModuleContext: Context? = null

    private fun moduleContext(context: Context): Context? {
        cachedModuleContext?.let { return it }
        return runCatching {
            context.createPackageContext(
                ModuleContract.MODULE_PACKAGE,
                Context.CONTEXT_IGNORE_SECURITY,
            )
        }.getOrNull()?.also { cachedModuleContext = it }
    }

    private fun View.setNativeChoiceEnabled(enabled: Boolean) {
        isEnabled = enabled
        isClickable = enabled
        alpha = if (enabled) ENABLED_ALPHA else DISABLED_ALPHA
    }

    private fun View.setSelectedTree(selected: Boolean) {
        isSelected = selected
        if (this !is ViewGroup) return
        for (index in 0 until childCount) getChildAt(index).setSelectedTree(selected)
    }

    private val COMPONENT = "MiLinkUi"
    private val SOUND_EFFECT_LABEL = "音效"
    private val EFFECT_LABELS = mapOf(
        FloS2SoundEffect.STANDARD to "标准",
        FloS2SoundEffect.SWIMMING to "游泳",
        FloS2SoundEffect.VOCALS to "人声",
        FloS2SoundEffect.ENHANCED to "增强",
    )
    private val EFFECT_ICON_NAMES = mapOf(
        FloS2SoundEffect.STANDARD to "ic_flos2_effect_standard",
        FloS2SoundEffect.SWIMMING to "ic_flos2_effect_swimming",
        FloS2SoundEffect.VOCALS to "ic_flos2_effect_vocals",
        FloS2SoundEffect.ENHANCED to "ic_flos2_effect_enhanced",
    )
    private const val ENABLED_ALPHA = 1.0f
    private const val DISABLED_ALPHA = 0.45f
}

internal object FloS2SoundEffectCardPolicy {
    data class CardState(
        val effect: FloS2SoundEffect?,
        val enabled: Boolean,
    )

    fun render(state: EarbudState): CardState {
        val feature = state.features.get<FloS2SoundEffectFeatureState>()
        return CardState(
            effect = feature?.effect,
            enabled = state.sessionActive && state.connected && feature != null,
        )
    }

    fun request(state: EarbudState, effect: FloS2SoundEffect): FloS2SoundEffect? {
        val current = render(state)
        if (!current.enabled || current.effect == effect) return null
        return effect
    }
}
