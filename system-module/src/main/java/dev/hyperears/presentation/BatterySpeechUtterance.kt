package dev.hyperears.presentation

import dev.hyperears.settings.BatterySpeechMode

internal interface SpeechScheduler {
    fun now(): Long
    fun post(task: Runnable)
    fun after(task: Runnable, milliseconds: Long)
    fun cancel(task: Runnable)
}
internal interface SpeechEngine {
    fun prepare(): Boolean
    fun speak(text: String, volume: Float, utteranceId: String): Boolean
    fun stop()
    fun shutdown()
}
internal fun interface SpeechEngineFactory {
    fun create(initialized: (Boolean) -> Unit, completed: (String) -> Unit): SpeechEngine
}
internal interface SpeechAudioGate {
    fun allowed(address: String, mode: BatterySpeechMode): Boolean
    fun watch(changed: () -> Unit): Boolean
    fun requestFocus(lost: () -> Unit): Boolean
    fun abandonFocus()
    fun close()
}

/** Each accepted utterance exclusively owns its engine, callbacks, deadline and focus. */
internal class BatterySpeechUtterance(
    private val scheduler: SpeechScheduler,
    private val engines: SpeechEngineFactory,
    private val gates: () -> SpeechAudioGate,
) : BatterySpeechHost {
    private class Job(val id: String, val gate: SpeechAudioGate, val address: String, val mode: BatterySpeechMode,
        val volume: Float, val message: BatterySpeechMessage, val valid: () -> Boolean, val finished: () -> Unit) {
        var engine: SpeechEngine? = null
        var closed = false
        var initialized = false
        var requestingFocus = false
        var focusGranted = false
        lateinit var deadline: Runnable
    }
    private var current: Job? = null
    private var sequence = 0L
    override fun now() = scheduler.now()
    override fun allowed(address: String, mode: BatterySpeechMode) = runCatching { gates().allowed(address, mode) }.getOrDefault(false)
    override fun speak(address: String, mode: BatterySpeechMode, volume: Float, message: BatterySpeechMessage,
        valid: () -> Boolean, finished: () -> Unit): Boolean {
        stop()
        val gate = gates()
        val job = Job("hyperears-battery-${++sequence}", gate, address, mode, volume, message, valid, finished)
        current = job
        job.deadline = Runnable { close(job) }
        if (!safe(job) || volume !in 0f..1f) { close(job); return false }
        scheduler.after(job.deadline, 10_000)
        if (!runCatching { gate.watch { if (!safe(job)) close(job) } }.getOrDefault(false) || !safe(job)) {
            close(job); return false
        }
        val engine = runCatching { engines.create(
            { success -> scheduler.post(Runnable { initialized(job, success) }) },
            { id -> scheduler.post(Runnable { if (id == job.id) close(job) }) },
        ) }.getOrElse { close(job); return false }
        // A synchronous callback may cancel during creation; release only the returned old engine.
        if (job.closed) { releaseEngine(engine); return false }
        job.engine = engine
        return true
    }
    private fun safe(job: Job) = current === job && !job.closed && runCatching {
        job.valid() && job.gate.allowed(job.address, job.mode)
    }.getOrDefault(false)
    private fun initialized(job: Job, success: Boolean) {
        if (job.closed || current !== job || job.initialized) return
        job.initialized = true
        val engine = job.engine ?: return
        if (!success || !safe(job) || !runCatching { engine.prepare() }.getOrDefault(false)) { close(job); return }
        if (!safe(job)) { close(job); return }
        job.requestingFocus = true
        val granted = runCatching { job.gate.requestFocus { close(job) } }.getOrDefault(false)
        job.requestingFocus = false
        job.focusGranted = granted
        if (job.closed) { releaseFocus(job); return }
        if (!granted || !safe(job)) { close(job); return }
        // SUCCESS means enqueued, never evidence that speech was audible.
        if (!runCatching { engine.speak(job.message.text(), job.volume, job.id) }.getOrDefault(false)) close(job)
    }
    override fun stop() { current?.let(::close) }
    private fun close(job: Job) {
        if (job.closed) return
        job.closed = true
        if (current === job) current = null
        scheduler.cancel(job.deadline)
        runCatching { job.gate.close() }
        job.engine?.let(::releaseEngine); job.engine = null
        if (!job.requestingFocus) releaseFocus(job)
        runCatching { job.finished() }
    }
    private fun releaseFocus(job: Job) {
        if (job.focusGranted) { job.focusGranted = false; runCatching { job.gate.abandonFocus() } }
    }
    private fun releaseEngine(engine: SpeechEngine) {
        runCatching { engine.stop() }; runCatching { engine.shutdown() }
    }
}
