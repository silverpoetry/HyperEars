package dev.hyperears.runtime

import dev.hyperears.integration.*

internal data class WrittenControlEffects(val effects: List<AdapterEffect>, val localStateChanged: Boolean)

/** Local workflow ownership may change after write without claiming a confirmed device result. */
internal fun writtenControlEffects(adapter: EarbudAdapter, request: ControlRequest): WrittenControlEffects {
    val before = adapter.runtimeState()
    val effects = adapter.controlWritten(request)
    return WrittenControlEffects(effects, adapter.runtimeState() != before)
}
