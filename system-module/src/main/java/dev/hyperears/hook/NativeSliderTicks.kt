package dev.hyperears.hook

/** Preserve small device scales; percentage controls use ten intervals. */
internal fun nativeSliderTicks(range: IntRange): List<Int> {
    if(range.isEmpty()) return emptyList()
    val step = if (range == 0..15) 3 else maxOf(1,(range.last-range.first+9)/10)
    return (range.step(step).toList()+range.last).distinct()
}

/** Every volume step stays visible; only major ticks carry numbers. */
internal fun nativeSliderMarks(range: IntRange): List<Int> =
    if (range == 0..15) range.toList() else nativeSliderTicks(range)
