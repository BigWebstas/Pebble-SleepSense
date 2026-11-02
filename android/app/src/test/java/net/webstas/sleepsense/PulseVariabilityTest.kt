package net.webstas.sleepsense

import java.io.File
import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PulseVariabilityTest {
    private val start = 1_700_000_000_000L

    // A pulse wave at 50 samples a second, in frames of 5 like the oximeter's, with beats [rr] seconds apart
    private fun waveFile(seconds: Int, rr: (Int) -> Double): File {
        val beats = ArrayList<Double>()
        var t = 0.5
        var i = 0
        while (t < seconds) { beats += t; t += rr(i++) }
        val file = File.createTempFile("wave", ".csv").apply { deleteOnExit() }
        file.bufferedWriter().use { out ->
            val frames = seconds * 10
            for (f in 0 until frames) {
                val values = (0 until 5).map { k ->
                    val time = (f * 5 + k) / 50.0
                    var v = 40.0
                    for (b in beats) if (kotlin.math.abs(time - b) < 0.5) v += 60 * exp(-(time - b) * (time - b) / (2 * 0.06 * 0.06))
                    v.toInt().coerceIn(0, 127)
                }
                out.write("${start + f * 100L},${values.joinToString(",")}\n")
            }
        }
        return file
    }

    @Test
    fun alternatingBeatsGiveTheirRmssd() {
        // Intervals alternate 0.785 and 0.815 s: successive differences are 30 ms
        val file = waveFile(660) { if (it % 2 == 0) 0.785 else 0.815 }
        val windows = PulseVariability.windows(file, start, start + 660_000L)
        assertTrue("expected two windows, got ${windows.size}", windows.size >= 2)
        windows.forEach { assertEquals(30.0, it.rmssdMs, 6.0) }
    }

    @Test
    fun steadyBeatsGiveAlmostNone() {
        val file = waveFile(660) { 0.8 }
        val windows = PulseVariability.windows(file, start, start + 660_000L)
        assertTrue(windows.isNotEmpty())
        windows.forEach { assertTrue("rmssd ${it.rmssdMs}", it.rmssdMs < 8.0) }
    }

    @Test
    fun aShortRunGivesNoWindow() {
        val file = waveFile(120) { 0.8 }
        assertTrue(PulseVariability.windows(file, start, start + 120_000L).isEmpty())
    }
}
