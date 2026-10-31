package dev.hyperears.runtime

import dev.hyperears.integration.EarbudTransportSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Keep the cooldown in the candidate job so disconnect/handoff cancels it before socket.connect. */
internal suspend fun EarbudChannel.connectAfterCooldown(spec: EarbudTransportSpec, stillOwned: () -> Boolean) {
    if (spec.connectDelayMs > 0) delay(spec.connectDelayMs)
    currentCoroutineContext().ensureActive()
    if (!stillOwned()) throw CancellationException("private candidate ownership changed")
    connect()
}
