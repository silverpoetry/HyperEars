package dev.hyperears.hook

import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class FloS2NativeAncHostTest {
    @Test
    fun failedSecondInsertionRestoresOriginalHost() {
        val fixture = Fixture()
        fixture.container.failOn = fixture.replacements[1]

        assertFalse(fixture.install())

        fixture.assertRestored()
        fixture.restore()
        fixture.assertRestored()
    }

    @Test
    fun failureAfterAttachmentAlsoRemovesTheThrowingReplacement() {
        val fixture = Fixture()
        fixture.container.failOn = fixture.replacements[1]
        fixture.container.failAfterAttachment = true

        assertFalse(fixture.install())

        fixture.assertRestored()
        fixture.restore()
        fixture.assertRestored()
    }

    @Test
    fun successfulInstallAndRepeatedRestorePreserveOriginalHost() {
        val fixture = Fixture()

        assertTrue(fixture.install())
        assertEquals("Sound effects", fixture.title.text.toString())
        fixture.replacements.forEach { assertSame(fixture.container, it.parent) }
        fixture.originals.forEach { assertNull(it.parent) }

        fixture.restore()
        fixture.assertRestored()
        fixture.restore()
        fixture.assertRestored()
    }

    private class Fixture {
        private val context = RuntimeEnvironment.getApplication()
        val container = FailingContainer(context)
        val title = TextView(context).apply { text = "Noise control" }
        val originals = List(3) { index ->
            View(context).apply {
                layoutParams = LinearLayout.LayoutParams(20 + index, 30 + index).apply {
                    leftMargin = index + 1
                }
                visibility = listOf(View.VISIBLE, View.INVISIBLE, View.GONE)[index]
            }
        }
        private val originalParams = originals.map { it.layoutParams }
        private val originalVisibility = originals.map { it.visibility }
        // Unrelated siblings catch incorrect restoration to a contiguous block or append-only order.
        private val originalChildren = listOf(
            View(context), originals[0], View(context), originals[1], originals[2], View(context),
        )
        val replacements = List(4) { View(context) }
        private val host: Any

        init {
            originalChildren.forEach(container::addView)
            // Keep production encapsulation: only construct the already-resolved host under test.
            val row = NativeAncModeRow::class.java.getDeclaredConstructor(
                NativeAncModeLayout::class.java, LinearLayout::class.java, List::class.java,
            ).apply { isAccessible = true }.newInstance(
                NativeAncModeLayout.ORIGINAL, container, originals,
            )
            val hostType = NativeAncModeHost::class.java
            host = hostType.getDeclaredConstructor(TextView::class.java, NativeAncModeRow::class.java)
                .apply { isAccessible = true }.newInstance(title, row)
        }

        fun install(): Boolean = host.javaClass.getDeclaredMethod(
            "install", String::class.java, List::class.java,
        ).apply { isAccessible = true }.invoke(host, "Sound effects", replacements) as Boolean

        fun restore() {
            host.javaClass.getDeclaredMethod("restore").apply { isAccessible = true }.invoke(host)
        }

        fun assertRestored() {
            assertEquals("Noise control", title.text.toString())
            assertEquals(originalChildren, (0 until container.childCount).map(container::getChildAt))
            originals.forEachIndexed { index, view ->
                assertSame(originalParams[index], view.layoutParams)
                assertEquals(originalVisibility[index], view.visibility)
            }
            replacements.forEach { assertNull(it.parent) }
        }
    }

    private class FailingContainer(context: Context) : LinearLayout(context) {
        var failOn: View? = null
        var failAfterAttachment = false

        override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams?) {
            val shouldFail = child === failOn
            if (shouldFail && !failAfterAttachment) error("Host rejected replacement")
            super.addView(child, index, params)
            if (shouldFail) error("Host callback failed after attachment")
        }
    }
}
