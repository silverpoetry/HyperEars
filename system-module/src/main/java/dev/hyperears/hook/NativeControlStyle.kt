package dev.hyperears.hook
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
internal object NativeControlStyle {
    fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
    fun dark(context: Context) = context.resources.configuration.uiMode and 0x30 == 0x20
    fun primary(context: Context) = if (dark(context)) Color.WHITE else Color.rgb(25,25,25)
    fun surface(context: Context) = if (dark(context)) Color.rgb(50,50,50) else Color.rgb(239,240,243)
    const val ACCENT = 0xff3482ff.toInt()
}
