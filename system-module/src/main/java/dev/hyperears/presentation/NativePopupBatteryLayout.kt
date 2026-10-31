package dev.hyperears.presentation

import android.annotation.SuppressLint
import android.view.View
import android.widget.RelativeLayout
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Both battery layouts already live in the native content View; switching needs no new Dialog. */
internal class NativePopupBatteryLayout private constructor(
    val render: Method,
    private val ids: Map<String, Int>,
) {
    fun apply(root: View, projection: NativeHeadsetProjection): Boolean {
        // Validate every named container before making any change. Never infer a View hierarchy.
        val containers = ids.mapValues { (name, id) ->
            require(root.resources.getResourceTypeName(id) == "id")
            require(root.resources.getResourceEntryName(id) == name)
            requireNotNull(root.findViewById<View>(id) as? RelativeLayout) {
                "Native battery container unavailable: $name"
            }
        }
        var changed = false
        containers.forEach { (name, view) ->
            val visible = when (name) {
                SINGLE -> projection.singleBattery
                EARS -> !projection.singleBattery
                else -> projection.showCaseInPopup
            }
            val visibility = if (visible) View.VISIBLE else View.GONE
            changed = changed || view.visibility != visibility
            view.visibility = visibility
        }
        return changed
    }

    companion object {
        private const val IMAGE = "imageViewBoxLayout"
        private const val BATTERY = "textViewBoxBattery"
        private const val SINGLE = "textViewHeadsetBatterySingle"
        private const val EARS = "textViewHeadsetBattery"
        private val CONTAINERS = listOf(IMAGE, BATTERY, SINGLE, EARS)

        // Exact R classes from the verified host plugins; numeric IDs are read from the loaded APK.
        private val RESOURCE_CLASSES = listOf(
            "com.xiaomi.bluetooth.peripheral.R\$id",
            "com.xiaomi.bluetooth.sample.preloadedFeature.R\$id",
        )

        // These are the scoped host's plugin classes, not Android hidden framework APIs.
        @SuppressLint("PrivateApi")
        fun resolve(controller: Class<*>, loader: ClassLoader): NativePopupBatteryLayout {
            val render = controller.getDeclaredMethod("setConnectSuccessLayoutDetail", View::class.java)
                .apply { isAccessible = true }
            require(render.returnType == Void.TYPE)
            for (className in RESOURCE_CLASSES) {
                val ids = runCatching {
                    val type = Class.forName(className, false, loader)
                    fun id(name: String): Int {
                        val field = type.getDeclaredField(name).apply { isAccessible = true }
                        require(Modifier.isStatic(field.modifiers) && field.type == Int::class.javaPrimitiveType)
                        return field.getInt(null).also { require(it != 0) }
                    }
                    CONTAINERS.associateWith(::id)
                }.getOrNull() ?: continue
                require(ids.values.toSet().size == CONTAINERS.size)
                return NativePopupBatteryLayout(render, ids)
            }
            throw NoSuchFieldException("Native battery layout contract unavailable")
        }
    }
}
