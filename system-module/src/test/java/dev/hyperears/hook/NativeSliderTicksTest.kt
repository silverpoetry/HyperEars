package dev.hyperears.hook
import org.junit.Assert.assertEquals
import org.junit.Test
class NativeSliderTicksTest {
    @Test fun percentMarksRemainReadableAndCoralKeepsItsScale() {
        assertEquals((0..100 step 10).toList(),nativeSliderTicks(0..100))
        assertEquals((0..6).toList(),nativeSliderTicks(0..6))
        assertEquals(listOf(5),nativeSliderTicks(5..5))
        assertEquals(99,nativeSliderTicks(0..99).last())
    }
    @Test fun volumeLabelsAreMultiplesOfThreeButEveryStepHasAMark() {
        assertEquals(listOf(0,3,6,9,12,15), nativeSliderTicks(0..15))
        assertEquals((0..15).toList(), nativeSliderMarks(0..15))
        assertEquals(nativeSliderTicks(0..100), nativeSliderMarks(0..100))
    }
}
