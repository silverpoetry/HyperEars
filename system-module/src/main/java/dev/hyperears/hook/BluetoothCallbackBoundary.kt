package dev.hyperears.hook

/** Posted callbacks execute outside Xposed's hook exception boundary. Keep module
 * initialization/session failures out of the Bluetooth service's main looper.
 * VM resource failures are deliberately not swallowed.
 */
internal fun runBluetoothCallback(failed: (Throwable) -> Unit, action: () -> Unit) {
    try {
        action()
    } catch (failure: Exception) {
        failed(failure)
    } catch (failure: LinkageError) {
        failed(failure)
    }
}
