package dev.hyperears.presentation

/** Cache evidence for a live session; unavailable readers and transient errors are never evidence. */
internal class NativeAdmissionCache {
    private val entries = mutableMapOf<String, Pair<String, NativeAdmission>>()

    fun read(address: String, session: String, refresh: Boolean = false, reader: () -> NativeAdmission): NativeAdmission {
        if (!refresh) entries[address]?.takeIf { it.first == session }?.let { return it.second }
        val admission = reader()
        if (admission == NativeAdmission.UNKNOWN) entries.remove(address)
        else entries[address] = session to admission
        return admission
    }

    fun remove(address: String) { entries.remove(address) }
    fun clear() = entries.clear()
}
