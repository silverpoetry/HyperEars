package dev.hyperears.presentation

/** Consume an authenticated case edge once; ordinary state replay must not reopen a popup. */
internal class AppleCasePresentationGate {
    private val seen = linkedMapOf<String, Pair<String, Long>>()
    fun accept(address: String, session: String, sequence: Long, open: Boolean,
        metadata: NativePresentationMetadata, now: Long): Boolean {
        val previous = seen[address]
        if (previous?.first == session && sequence <= previous.second) return false
        seen[address] = session to sequence
        while (seen.size > 128) seen.remove(seen.keys.first())
        return sequence > 0 && open && !metadata.replay && metadata.admission == NativeAdmission.HYPEREARS &&
            now - metadata.connection.connectedAtElapsedMs > 10_000
    }
    fun clear() = seen.clear()
}
