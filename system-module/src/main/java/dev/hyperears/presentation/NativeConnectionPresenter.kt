package dev.hyperears.presentation

import android.bluetooth.BluetoothDevice
import java.lang.reflect.Method

internal sealed interface NativeHintSource {
    data class AirPods(val stateReader: Method) : NativeHintSource
    data class LegacyXiaomi(val notificationType: Method, val inlineBoundaries: List<Method>) : NativeHintSource
}

internal interface NativeConnectionPresenter {
    /** The supplier revalidates the connection and returns its latest accepted state at dispatch. */
    fun show(
        projection: NativeHeadsetProjection,
        device: BluetoothDevice,
        latest: () -> NativeHeadsetProjection?,
    ): NativePresentationResult
}
