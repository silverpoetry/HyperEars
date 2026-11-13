package dev.hyperears.presentation

import android.annotation.SuppressLint
import android.content.Context
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Reads the native desktop/cover-screen decision without entering its device connection manager. */
internal class NativePresentationScene private constructor(private val homeScreen: Method) {
    fun isOnHomeOrCoverScreen(context: Context): Boolean = homeScreen.invoke(null, context) as Boolean

    companion object {
        // This is the explicitly scoped host's feature plugin, not an Android framework API.
        @SuppressLint("PrivateApi")
        fun resolve(loader: ClassLoader, legacy: Boolean = false): NativePresentationScene {
            if (legacy) {
                val utility = Class.forName("${NativeWindowBridge.PREFIX}.Util", false, loader)
                val method = utility.getDeclaredMethod("isInLauncher", Context::class.java)
                require(Modifier.isStatic(method.modifiers) && method.returnType == Boolean::class.javaPrimitiveType)
                return NativePresentationScene(method.apply { isAccessible = true })
            }
            val type = Class.forName(
                "${AirPodsWindowBridge.PREFIX}.airpods.HomeScreenChecker", false, loader,
            )
            // These are the explicit semantic entry points in the verified new and older plugins.
            val method = try {
                type.getDeclaredMethod("isOnHomeOrCoverScreen", Context::class.java)
            } catch (_: NoSuchMethodException) {
                type.getDeclaredMethod("isOnHomeScreen", Context::class.java)
            }
            require(Modifier.isStatic(method.modifiers) && method.returnType == Boolean::class.javaPrimitiveType)
            method.isAccessible = true
            return NativePresentationScene(method)
        }
    }
}
