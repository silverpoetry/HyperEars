package dev.hyperears.hook

import android.os.Looper
import android.view.View
import dev.hyperears.integration.ControlRequest
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.MiLinkCardPresentationId
import dev.hyperears.integration.StandardControlRequest
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.Locale

/**
 * Routes a native MiLink card to the presentation adapter selected by its concrete device adapter.
 *
 * This coordinator contains no model-specific view logic and performs no layout polling.
 */
internal class MiLinkHeadsetDetailExtension(
    hostClassLoader: ClassLoader,
    stateProvider: (String) -> EarbudState,
    controlSender: (String, ControlRequest) -> Unit,
) {
    private class Target(
        root: View,
        var address: String,
        var publishedPresentationId: MiLinkCardPresentationId?,
    ) {
        val root = WeakReference(root)
        var boundPresentationId: MiLinkCardPresentationId? = null
        var binding: MiLinkCardBinding? = null
    }

    private val stateObservers = linkedMapOf<Any, Pair<String, (EarbudState) -> Unit>>()
    private val environment = MiLinkCardEnvironment(
        hostClassLoader = hostClassLoader,
        stateProvider = stateProvider,
        controlSender = controlSender,
        observeState = { address, observer ->
            val key = Any()
            stateObservers[key] = address.uppercase(Locale.ROOT) to observer
            val unsubscribe: () -> Unit = { stateObservers.remove(key) }
            unsubscribe
        },
    )
    private val targetLock = Any()
    private val targets = WeakHashMap<View, Target>()

    fun bind(
        root: View,
        address: String,
        publishedPresentationId: MiLinkCardPresentationId?,
    ) {
        val normalizedAddress = address.uppercase(Locale.ROOT)
        val target = synchronized(targetLock) {
            targets[root]?.also { existing ->
                if (existing.address != normalizedAddress) {
                    release(existing)
                    existing.address = normalizedAddress
                }
                if (existing.publishedPresentationId != publishedPresentationId) {
                    release(existing)
                    existing.publishedPresentationId = publishedPresentationId
                }
            } ?: Target(
                root = root,
                address = normalizedAddress,
                publishedPresentationId = publishedPresentationId,
            ).also { created ->
                targets[root] = created
                installAttachLifecycle(root, created)
            }
        }
        dispatchRender(target)
        requestRefresh(target)
        root.post {
            if (root.isAttachedToWindow) render(target)
        }
    }

    private fun installAttachLifecycle(root: View, target: Target) {
        root.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    dispatchRender(target)
                    requestRefresh(target)
                }

                override fun onViewDetachedFromWindow(view: View) {
                    release(target)
                }
            },
        )
    }

    fun onStateChanged(state: EarbudState) {
        val address = state.address?.uppercase(Locale.ROOT) ?: return
        // State receivers and popup lifecycle run on the main thread. Snapshot before delivery:
        // a disconnected state can dismiss a popup and remove its subscription reentrantly.
        stateObservers.values.toList().filter { it.first == address }.forEach { (_, observer) ->
            runCatching { observer(state) }.onFailure {
                ModuleLog.warn("MiLinkUi", "details state observer failed rev=${state.revision}", it)
            }
        }
        val matchingTargets = synchronized(targetLock) {
            targets.values.filter { it.address == address }
        }
        matchingTargets.forEach { target ->
            dispatchRender(target)
            val root = target.root.get() ?: return@forEach
            root.post {
                if (root.isAttachedToWindow) render(target)
            }
        }
    }

    /** Releases any optional card binding when MiLink reclaims the device natively. */
    fun unbind(address: String) {
        val normalizedAddress = address.uppercase(Locale.ROOT)
        val matchingTargets = synchronized(targetLock) {
            targets.values.filter { it.address == normalizedAddress }
        }
        matchingTargets.forEach { target ->
            val root = target.root.get() ?: return@forEach
            root.post {
                target.publishedPresentationId = null
                release(target)
            }
        }
    }

    private fun dispatchRender(target: Target) {
        val root = target.root.get() ?: return
        if (!root.isAttachedToWindow) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            render(target)
        } else {
            root.post { render(target) }
        }
    }

    /** Requests fresh device state once when the common native card becomes visible. */
    private fun requestRefresh(target: Target) {
        val root = target.root.get() ?: return
        if (!root.isAttachedToWindow) return
        val state = environment.stateProvider(target.address)
        if (!state.sessionActive) return
        environment.controlSender(target.address, StandardControlRequest.Refresh)
    }

    private fun render(target: Target) {
        val root = target.root.get() ?: return
        if (!root.isAttachedToWindow) return
        val retainedState = environment.stateProvider(target.address)
        val localState = retainedState.takeIf(EarbudState::sessionActive)
        val presentationId = if (localState != null) {
            // An active Adapter's null presentation is deliberate (for example, after a failed
            // family probe falls back to standard integration). Cached service metadata must not
            // resurrect the previous model-specific binding.
            localState.adapter?.presentationId
        } else {
            target.publishedPresentationId
        }
        if (target.boundPresentationId != presentationId) {
            release(target)
        }
        val cardAdapter = presentationId
            ?.let(MiLinkCardAdapterRegistry::resolve)
        if (cardAdapter == null) return
        if (target.binding == null) {
            target.binding = cardAdapter.bind(root, target.address, environment)
            target.boundPresentationId = presentationId.takeIf { target.binding != null }
        }
        target.binding?.render(localState ?: EarbudState())
    }

    private fun release(target: Target) {
        target.binding?.unbind()
        target.binding = null
        target.boundPresentationId = null
    }
}
