package dev.hyperears.presentation

import java.util.UUID

/** Publication receipts are synchronous; Android's visible notification list is not. */
internal class NativeNotificationRegistry(private val newToken: () -> String = { UUID.randomUUID().toString() }) {
    data class Publication(
        val projection: NativeHeadsetProjection,
        val connection: String,
        val token: String,
    )

    /** null token means a visible native-owned notification; null Visible means no visible post. */
    data class Visible(val token: String?)

    private val entries = mutableMapOf<String, Publication>()
    val addresses: List<String> get() = entries.keys.toList()

    fun prepare(
        projection: NativeHeadsetProjection,
        connection: String,
        visible: Visible?,
    ): Publication? {
        if (!projection.hasBattery || (visible != null && visible.token == null)) return null
        val previous = entries[projection.address]
        if (visible != null && visible.token == previous?.token &&
            previous?.projection == projection && previous.connection == connection) return null
        // All reports for this physical connection share the click identity, including aggregate
        // -> components and private protocol session replacement. A reconnect gets a new token.
        val token = previous?.takeIf { it.connection == connection }?.token ?: newToken()
        return Publication(projection, connection, token)
    }

    fun posted(publication: Publication) {
        entries[publication.projection.address] = publication
    }

    fun forClick(address: String, token: String): Publication? =
        entries[address]?.takeIf { it.token == token }

    fun retire(address: String, visible: Visible?): Publication? {
        val entry = entries.remove(address) ?: return null
        // Cancel our pending post even before it appears. A native replacement is never ours.
        // An older marked post may still be visible while our latest update is queued.
        return entry.takeIf { visible == null || visible.token != null }
    }
}
