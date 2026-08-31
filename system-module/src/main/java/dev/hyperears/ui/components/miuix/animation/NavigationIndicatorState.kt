package dev.hyperears.ui.components.miuix.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A navigation indicator with direct drag tracking and non-overshooting selection animation. */
@Stable
internal class NavigationIndicatorState(
    private val coroutineScope: CoroutineScope,
    initialValue: Float,
    private val valueRange: ClosedFloatingPointRange<Float>,
) {
    var value by mutableFloatStateOf(initialValue.coerceIn(valueRange))
        private set

    private var animationJob: Job? = null

    fun stopAnimation() {
        animationJob?.cancel()
        animationJob = null
    }

    fun dragBy(delta: Float) {
        stopAnimation()
        value = (value + delta).coerceIn(valueRange)
    }

    fun animateTo(targetValue: Float) {
        val target = targetValue.coerceIn(valueRange)
        if (target == value) return
        stopAnimation()
        animationJob = coroutineScope.launch {
            val animation = Animatable(value)
            animation.animateTo(
                targetValue = target,
                animationSpec = tween(
                    durationMillis = 200,
                    easing = FastOutSlowInEasing,
                ),
            ) {
                this@NavigationIndicatorState.value = this.value
            }
            animationJob = null
        }
    }
}
