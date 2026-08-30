package dev.hyperears.runtime

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import dev.hyperears.integration.EarbudIdentity
import java.util.Locale

/**
 * Builds the platform-independent identity used by the Adapter registry.
 *
 * Bluetooth class is authoritative when available. A conservative name fallback covers earbuds
 * that report an uncategorized audio class; speakers and car audio are intentionally excluded.
 */
@SuppressLint("MissingPermission")
internal fun BluetoothDevice.toEarbudIdentity(): EarbudIdentity {
    val deviceName = runCatching { name ?: alias }.getOrNull()
    val deviceClass = runCatching { bluetoothClass?.deviceClass }.getOrNull()
    return EarbudIdentity(
        deviceName = deviceName,
        standardHeadset =
            deviceClass in HEADSET_DEVICE_CLASSES ||
                isLikelyEarbudName(deviceName),
        nativeSystemEarbud = isNativeXiaomiEarbudName(deviceName),
        deviceAddress = runCatching { address }.getOrNull(),
        bluetoothDeviceClass = deviceClass,
        serviceUuids = runCatching {
            uuids.orEmpty()
                .mapTo(linkedSetOf()) { it.uuid.toString().lowercase(Locale.ROOT) }
        }.getOrDefault(emptySet()),
    )
}

internal fun isLikelyEarbudName(deviceName: String?): Boolean {
    val normalized = deviceName
        ?.lowercase(Locale.ROOT)
        ?.filter(Char::isLetterOrDigit)
        .orEmpty()
    if (normalized.isEmpty()) return false
    return HEADSET_NAME_MARKERS.any(normalized::contains)
}

internal fun isNativeXiaomiEarbudName(deviceName: String?): Boolean {
    val normalized = deviceName
        ?.lowercase(Locale.ROOT)
        ?.filter(Char::isLetterOrDigit)
        .orEmpty()
    if (normalized.isEmpty()) return false
    return XIAOMI_EARBUD_NAME_MARKERS.any(normalized::contains)
}

private val HEADSET_DEVICE_CLASSES = setOf(
    BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
    BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE,
    BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
)

private val HEADSET_NAME_MARKERS = setOf(
    "tws",
    "earbud",
    "earphone",
    "headset",
    "headphone",
    "airpod",
    "buds",
    "freebuds",
    "freeclip",
    "enco",
    "耳机",
    "耳麦",
)

private val XIAOMI_EARBUD_NAME_MARKERS = setOf(
    "xiaomi",
    "redmi",
    "mitruewireless",
    "miair",
    "mibuds",
)
