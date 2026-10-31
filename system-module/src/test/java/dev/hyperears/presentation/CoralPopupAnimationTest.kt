package dev.hyperears.presentation

import android.app.Activity
import android.widget.ImageView
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class CoralPopupAnimationTest {
    @Test fun closeBeforeAttachLeavesNoDeferredAnimation() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val ears = ImageView(activity)
        val box = ImageView(activity)
        val animation = CoralPopupAnimation(ears, box)
        animation.attach()
        animation.close()
        activity.setContentView(LinearLayout(activity).apply { addView(ears); addView(box) })
        assertEquals(1f, ears.alpha)
        assertEquals(0f, ears.translationY)
        assertEquals(1f, box.alpha)
        controller.pause().stop().destroy()
    }

    @Test fun closingRestoresBothStockSlotsAndIsIdempotent() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val ears = ImageView(activity)
        val box = ImageView(activity)
        activity.setContentView(LinearLayout(activity).apply { addView(ears); addView(box) })
        val animation = CoralPopupAnimation(ears, box)
        animation.attach()
        animation.close()
        animation.close()
        animation.attach()
        assertEquals(1f, ears.alpha)
        assertEquals(0f, ears.translationY)
        assertEquals(1f, box.alpha)
        controller.pause().stop().destroy()
    }
}
