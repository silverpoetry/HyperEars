package dev.hyperears.runtime

import kotlinx.coroutines.delay

/** Ordered writes with adapter-selected pacing; no wait before the first or after the last frame. */
internal suspend fun writeCommandSequence(
    commands: List<ByteArray>,
    gapMs: Long,
    wait: suspend (Long) -> Unit = { delay(it) },
    write: suspend (ByteArray) -> Unit,
) {
    require(gapMs >= 0L)
    commands.forEachIndexed { index, command ->
        write(command)
        if (gapMs > 0L && index != commands.lastIndex) wait(gapMs)
    }
}
