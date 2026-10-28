package dev.hyperears.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import dev.hyperears.integration.*
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native-rendered artifacts also expose clipping that text-value assertions cannot detect. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BatteryWidgetVisualTest {
    @Test fun compactWideAndLargeTextFitAndProduceReviewImages() {
        val app = RuntimeEnvironment.getApplication()
        val record = BatteryWidgetRecord("AA:BB:CC:DD:EE:01", "ROSE Coral", EarbudBattery(
            left = BatteryReading(100, true), right = BatteryReading(87, false), case = BatteryReading(62, true)),
            HeadsetFormFactor.TWS, BatteryWidgetStatus.CONNECTED, 1_790_000_000_000L, 10, true)
        val output = File("build/reports/widget-previews").apply { mkdirs() }
        data class Size(val name: String, val width: Int, val height: Int, val font: Float, val dark: Boolean = false)
        val sizes = listOf(Size("compact", 180, 160, 1f), Size("compact-minimum", 110, 90, 1f),
            Size("wide-minimum", 250, 100, 1f), Size("wide", 320, 110, 1f),
            Size("compact-large-text", 180, 180, 1.3f), Size("wide-dark-large-text", 360, 130, 1.3f, true))
        sizes.forEach { size ->
            val context = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                fontScale = size.font
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (size.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            })
            val options = Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, size.width)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, size.height)
            }
            val view = BatteryWidgetRenderer.render(context, 7, options, record, null).apply(context, FrameLayout(context))
            view.measure(View.MeasureSpec.makeMeasureSpec(size.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(size.height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, size.width, size.height)
            val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(output, "${size.name}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            fun check(child: View) {
                if (child.visibility != View.VISIBLE) return
                if (child is TextView) {
                    val rect = Rect(0, 0, child.width, child.height)
                    (view as ViewGroup).offsetDescendantRectToMyCoords(child, rect)
                    assertTrue("${size.name}: ${child.text} outside widget: $rect",
                        rect.left >= 0 && rect.top >= 0 && rect.right <= size.width && rect.bottom <= size.height)
                }
                if (child is ViewGroup) for (index in 0 until child.childCount) check(child.getChildAt(index))
            }
            check(view)
            bitmap.recycle()
        }
    }
}
