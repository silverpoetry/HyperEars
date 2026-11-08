package dev.hyperears.runtime

import dev.hyperears.integration.EarbudAdapter
import dev.hyperears.integration.ProtocolPacket
import kotlinx.coroutines.delay

internal fun EarbudAdapter.routeCommands(commands: List<ByteArray>): List<ProtocolPacket> =
    commands.flatMap { command ->
        val routes = protocolSession.commandRoutes(command)
        require(routes.isNotEmpty() && routes.distinct().size == routes.size) { "Invalid command routes" }
        routes.map { ProtocolPacket(command, it) }
    }

/** Returns the permitted unconfirmed error for diagnostics; null means the write did not fail. */
internal suspend fun writeAdapterCommand(
    adapter: EarbudAdapter,
    channel: EarbudChannel,
    packet: ProtocolPacket,
): GattWriteException? = try {
    channel.write(packet.bytes, packet.route)
    null
} catch (error: GattWriteException) {
    if (error.route != packet.route ||
        !adapter.permitsUnconfirmedGattWrite(packet.bytes, packet.route, error.status)
    ) throw error
    error
}

/** Ordered writes with adapter-selected pacing; no wait before the first or after the last frame. */
internal suspend fun <T> writeCommandSequence(
    commands: List<T>,
    gapMs: Long,
    wait: suspend (Long) -> Unit = { delay(it) },
    write: suspend (T) -> Unit,
) {
    require(gapMs >= 0L)
    commands.forEachIndexed { index, command ->
        write(command)
        if (gapMs > 0L && index != commands.lastIndex) wait(gapMs)
    }
}
