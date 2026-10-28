package dev.hyperears.hook

import android.annotation.SuppressLint
import android.view.View
import dev.hyperears.integration.ControlRequest
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.MiLinkCardPresentationId
import dev.hyperears.integration.NoiseMode

/**
 * A model-specific, one-shot adaptation of MiLink's already-created native headset card.
 *
 * The common coordinator owns lifecycle only. Each concrete implementation owns its view
 * contract and must not perform Bluetooth I/O or poll state.
 */
internal interface MiLinkCardAdapter {
    val presentationId: MiLinkCardPresentationId

    /** Stock MiLink section required as the presentation's visual and sizing carrier. */
    val nativeSurface: MiLinkNativeCardSurface get() = MiLinkNativeCardSurface.NONE

    /** State projected only into the stock carrier while the concrete CardAdapter owns its UI. */
    fun nativeSurfaceNoiseMode(state: EarbudState): NoiseMode? = null

    /**
     * Projects a model-specific mode onto MiLink's native three-state controller.
     *
     * The projection is presentation behavior, not protocol behavior: adapters that render an
     * extra mode in a native slot own that slot mapping. The default keeps ANC-branch extensions
     * such as StarRing WIND compatible with MiLink's stock state machine.
     */
    fun projectNativeNoiseMode(mode: NoiseMode?): NoiseMode? = when (mode) {
        NoiseMode.WIND -> NoiseMode.ANC
        else -> mode
    }

    fun bind(
        root: View,
        address: String,
        environment: MiLinkCardEnvironment,
    ): MiLinkCardBinding?
}

internal enum class MiLinkNativeCardSurface {
    NONE,
    ANC_THREE_STATE,
}

internal fun interface MiLinkCardBinding {
    fun render(state: EarbudState)

    /** Restores any host views replaced during [MiLinkCardAdapter.bind]. */
    fun unbind() = Unit
}

internal data class MiLinkCardEnvironment(
    val hostClassLoader: ClassLoader,
    val stateProvider: (String) -> EarbudState,
    val controlSender: (String, ControlRequest) -> Unit,
    /** Observe accepted state while a details window is open; returned callback unsubscribes. */
    val observeState: (String, (EarbudState) -> Unit) -> (() -> Unit),
)

/**
 * Single composition root for model-specific MiLink presentations.
 *
 * The lifecycle coordinator resolves opaque IDs through this registry and therefore never
 * imports concrete models or contains their view contracts.
 */
internal object MiLinkCardAdapterRegistry {
    private val adapters = listOf(
        StarRingUltraMiLinkCardAdapter,
        RoseLuliXMiLinkCardAdapter,
        RoseEarfreeI5MiLinkCardAdapter,
        RoseBudsFeelMk2MiLinkCardAdapter,
        NiceHckOrigMiLinkCardAdapter,
        BoseQuietComfortMiLinkCardAdapter,
        BoseAnrMiLinkCardAdapter,
        BoseTwoModeMiLinkCardAdapter,
        EdifierFourModeMiLinkCardAdapter,
        EdifierFitBudsTurboMiLinkCardAdapter,
        FitClipUltraGameModeMiLinkCardAdapter,
        FloS2SoundEffectMiLinkCardAdapter,
        SonyAmbientOnlyMiLinkCardAdapter,
        SamsungBuds2ProMiLinkCardAdapter,
    )
    private val byId = adapters.associateBy(MiLinkCardAdapter::presentationId)

    init {
        require(byId.size == adapters.size) {
            "MiLink card presentation IDs must be unique"
        }
    }

    fun resolve(id: MiLinkCardPresentationId): MiLinkCardAdapter? = byId[id]
}

@SuppressLint("DiscouragedApi")
internal fun View.findMiLinkView(name: String): View? {
    val id = resources.getIdentifier(name, "id", context.packageName)
        .takeIf { it != 0 }
        ?: resources.getIdentifier(name, "id", MILINK_PACKAGE)
    return id.takeIf { it != 0 }?.let(::findViewById)
}

/** Stable, non-obfuscated original MiLink item boundary. */
internal const val HOST_ANC_ITEM_CLASS =
    "com.miui.circulate.world.headset.ui.HeadsetControlAncItemView"
private const val MILINK_PACKAGE = "com.milink.service"
