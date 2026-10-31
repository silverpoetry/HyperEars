package dev.hyperears.integration

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val artworkNamePattern = Regex("headset_[a-z0-9_]{1,100}")

/** A bundled module drawable, independent of Android resource IDs and native device identity. */
@Serializable
@JvmInline
value class HeadsetArtworkId(val value: String) {
    init {
        require(artworkNamePattern.matches(value))
    }
}

/** Models inherit these defaults and may override either original, licensed illustration. */
@Serializable
data class HeadsetArtwork(
    val headset: HeadsetArtworkId,
    val chargingCase: HeadsetArtworkId? = null,
) {
    companion object {
        val EARBUDS = HeadsetArtwork(
            HeadsetArtworkId("headset_generic_earbuds"),
            HeadsetArtworkId("headset_generic_case"),
        )
        val HEADPHONES = HeadsetArtwork(HeadsetArtworkId("headset_generic_headphones"))

        fun defaultFor(form: HeadsetFormFactor): HeadsetArtwork = when (form) {
            HeadsetFormFactor.GLASSES -> HeadsetArtwork(HeadsetArtworkId("headset_generic_glasses"))
            HeadsetFormFactor.TWS -> EARBUDS
            HeadsetFormFactor.HEADPHONES -> HEADPHONES
        }
    }
}

object HeadsetArtworkTransport {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(artwork: HeadsetArtwork): String = json.encodeToString(artwork)
    fun decode(value: String): HeadsetArtwork? =
        value.takeIf { it.length <= 512 }?.let {
            runCatching { json.decodeFromString<HeadsetArtwork>(it) }.getOrNull()
        }
}
