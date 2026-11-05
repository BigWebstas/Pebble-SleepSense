package net.webstas.sleepsense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SnoozeAdviceTest {
    private fun night(i: Int, snoozes: Int, minutes: Int = snoozes * 10, length: Long = 480) = SnoozeNight(i * 1000L, length, snoozes, minutes)

    @Test fun fiveTenMinuteSnoozesMoveTheAlarmHalfAnHour() {
        val advice = SnoozeAdvice.compute(List(5) { night(it, 5) }, 15)!!
        assertEquals(30, advice.shiftMin) // 50 min of snoozing, 60% is 30
        assertEquals(30, advice.widerWindow)
        assertEquals(7 * 60 + 30, SnoozeAdvice.moved(7 * 60, advice.shiftMin))
    }

    @Test fun fewSnoozesGiveNoAdvice() {
        assertNull(SnoozeAdvice.compute(List(5) { night(it, 1) }, 30))
    }

    @Test fun needsThreeNights() {
        assertNull(SnoozeAdvice.compute(List(2) { night(it, 5) }, 30))
    }

    @Test fun napsAreLeftOut() {
        assertNull(SnoozeAdvice.compute(List(5) { night(it, 5, length = 45) }, 30))
    }

    @Test fun shiftIsAtLeastAQuarterHourAndAtMostAnHour() {
        assertEquals(15, SnoozeAdvice.compute(List(3) { night(it, 2, minutes = 10) }, null)!!.shiftMin)
        assertEquals(60, SnoozeAdvice.compute(List(3) { night(it, 9, minutes = 200) }, null)!!.shiftMin)
    }

    @Test fun noWiderWindowAtTheLargest() {
        assertNull(SnoozeAdvice.compute(List(3) { night(it, 4) }, 45)!!.widerWindow)
        assertNotNull(SnoozeAdvice.compute(List(3) { night(it, 4) }, 30)!!.widerWindow)
    }

    @Test fun movingPastMidnightWraps() {
        assertEquals(15, SnoozeAdvice.moved(23 * 60 + 45, 30))
    }
}
