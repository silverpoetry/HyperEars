package dev.hyperears.hook

/** Roll back a partially installed hook family without replacing the original installation error. */
internal class HookInstallTransaction<T>(private val remove: (T) -> Unit) {
    private val handles = java.util.concurrent.CopyOnWriteArrayList<T>()
    fun record(handle: T) { handles.add(handle) }
    fun install(block: () -> Unit) {
        try { block() } catch (error: Throwable) {
            handles.reversed().forEach { runCatching { remove(it) } }
            handles.clear()
            throw error
        }
    }
}
