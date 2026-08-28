package dev.hyperears.hook

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Diagnostic-only byte trace for a vendor controller app.
 *
 * The app process's Bluetooth socket lifecycle and byte streams are mirrored through
 * ModuleLog, so the exact handshake a vendor app performs can be recovered from an
 * exported diagnostics report. Logging is fully gated by the detailed-logging switch;
 * the hooks never modify arguments or results.
 */
internal class ControlAppSocketTrace : HookContext() {
    private val hookedStreamClasses = ConcurrentHashMap.newKeySet<String>()

    override fun install() {
        val device = findClassOrNull("android.bluetooth.BluetoothDevice") ?: return
        val socket = findClassOrNull("android.bluetooth.BluetoothSocket") ?: return

        hookAfter(findMethod(device.name, "createRfcommSocketToServiceRecord", UUID::class.java)) {
            ModuleLog.debug(COMPONENT) { "createRfcommSocketToServiceRecord uuid=${args[0]}" }
        }
        hookAfter(findMethod(device.name, "createInsecureRfcommSocketToServiceRecord", UUID::class.java)) {
            ModuleLog.debug(COMPONENT) { "createInsecureRfcommSocketToServiceRecord uuid=${args[0]}" }
        }
        hookBefore(findMethod(socket.name, "connect")) {
            ModuleLog.debug(COMPONENT) { "socket connect ${instance?.javaClass?.simpleName}" }
        }
        hookAfter(findMethod(socket.name, "getOutputStream")) {
            result?.let(::hookStreamWrites)
        }
        hookAfter(findMethod(socket.name, "getInputStream")) {
            result?.let(::hookStreamReads)
        }
        ModuleLog.debug(COMPONENT) { "socket trace active package=$packageName" }
    }

    private fun hookStreamWrites(stream: Any) {
        val clazz = stream.javaClass
        if (!hookedStreamClasses.add(clazz.name)) return
        clazz.declaredMethods.filter { it.name == "write" }.forEach { method ->
            method.isAccessible = true
            hookBefore(method) {
                val bytes = args.getOrNull(0) as? ByteArray ?: return@hookBefore
                val offset = (args.getOrNull(1) as? Int) ?: 0
                val length = (args.getOrNull(2) as? Int) ?: bytes.size
                val end = (offset + length).coerceIn(0, bytes.size)
                if (offset < end) {
                    ModuleLog.debug(COMPONENT) { "write bytes=${bytes.copyOfRange(offset, end).toHex()}" }
                }
            }
        }
    }

    private fun hookStreamReads(stream: Any) {
        val clazz = stream.javaClass
        if (!hookedStreamClasses.add(clazz.name)) return
        clazz.declaredMethods.filter { it.name == "read" }.forEach { method ->
            method.isAccessible = true
            hookAfter(method) {
                val count = (result as? Int) ?: return@hookAfter
                if (count <= 0) return@hookAfter
                val bytes = args.getOrNull(0) as? ByteArray ?: return@hookAfter
                val offset = (args.getOrNull(1) as? Int) ?: 0
                val length = count.coerceIn(0, bytes.size - offset)
                if (length > 0) {
                    ModuleLog.debug(COMPONENT) { "read bytes=${bytes.copyOfRange(offset, offset + length).toHex()}" }
                }
            }
        }
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

    private companion object {
        const val COMPONENT = "AppSocketTrace"
    }
}
