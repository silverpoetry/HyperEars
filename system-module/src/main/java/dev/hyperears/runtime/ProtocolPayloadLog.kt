package dev.hyperears.runtime

/** Redact the whole stream, since a key response may arrive without its header in this chunk. */
internal fun protocolPayloadLog(bytes: ByteArray, sensitive: Boolean): String =
    if (sensitive) "<redacted ${bytes.size} bytes>"
    else bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
