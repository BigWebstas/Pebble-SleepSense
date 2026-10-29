package net.webstas.sleepsense

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Breathing rate from the watch's accelerometer axes while it rests on the chest. Breathing tilts the watch, so
 * it shows in the axes (not in their magnitude). Each axis of a one-minute window is band-passed to 0.1 to
 * 0.55 Hz (6 to 33 breaths a minute), the direction with the most motion is taken, and the strongest frequency
 * in it is the rate. The tone is the share of the band's power within 0.03 Hz of that frequency: near 1 for a
 * steady breath, low when you move or breathe unevenly, in which case there is no reading.
 */
object BreathingRate {
    const val SAMPLES_PER_SECOND = 5
    const val WINDOW_SAMPLES = 60 * SAMPLES_PER_SECOND

    private const val LOW_HZ = 0.1
    private const val HIGH_HZ = 0.55
    private const val PEAK_STEP_HZ = 0.002
    private const val PEAK_WIDTH_HZ = 0.03
    private const val MIN_TONE = 0.5 // below this a reading is mostly noise: movement reads as the band's lower edge

    class Estimate(val perMinute: Double, val tone: Double) {
        val reliable get() = tone >= MIN_TONE
    }

    // Whole-window DFT bins inside the band, and their sine and cosine tables
    private val firstBin = Math.ceil(LOW_HZ * WINDOW_SAMPLES / SAMPLES_PER_SECOND - 1e-9).toInt()
    private val lastBin = Math.floor(HIGH_HZ * WINDOW_SAMPLES / SAMPLES_PER_SECOND + 1e-9).toInt()
    private val cosTable = Array(lastBin + 1) { k -> DoubleArray(WINDOW_SAMPLES) { cos(2 * PI * k * it / WINDOW_SAMPLES) } }
    private val sinTable = Array(lastBin + 1) { k -> DoubleArray(WINDOW_SAMPLES) { sin(2 * PI * k * it / WINDOW_SAMPLES) } }

    /** [axes] is x, y, z, each [WINDOW_SAMPLES] long, in the same unit. */
    fun estimate(axes: Array<DoubleArray>): Estimate {
        require(axes.size == 3 && axes.all { it.size == WINDOW_SAMPLES })
        val band = Array(3) { bandPass(axes[it]) }
        val main = mainDirection(band)
        val signal = DoubleArray(WINDOW_SAMPLES) { t -> main[0] * band[0][t] + main[1] * band[1][t] + main[2] * band[2][t] }

        // Hann-windowed spectrum of that signal over the band, on a fine grid
        val windowed = DoubleArray(WINDOW_SAMPLES) { signal[it] * (0.5 - 0.5 * cos(2 * PI * it / (WINDOW_SAMPLES - 1))) }
        val steps = ((HIGH_HZ - LOW_HZ) / PEAK_STEP_HZ).toInt() + 1
        val power = DoubleArray(steps) { i ->
            val w = 2 * PI * (LOW_HZ + i * PEAK_STEP_HZ) / SAMPLES_PER_SECOND
            var re = 0.0
            var im = 0.0
            for (t in 0 until WINDOW_SAMPLES) {
                re += windowed[t] * cos(w * t)
                im -= windowed[t] * sin(w * t)
            }
            re * re + im * im
        }
        val peak = power.indices.maxByOrNull { power[it] } ?: return Estimate(0.0, 0.0)
        val total = power.sum()
        if (total <= 0.0) return Estimate(0.0, 0.0)
        val near = power.indices.filter { abs((it - peak) * PEAK_STEP_HZ) <= PEAK_WIDTH_HZ }.sumOf { power[it] }
        return Estimate((LOW_HZ + peak * PEAK_STEP_HZ) * 60, near / total)
    }

    /** The window with its mean and everything outside the band removed (a mask on the DFT bins, zero phase). */
    private fun bandPass(x: DoubleArray): DoubleArray {
        val mean = x.average()
        val out = DoubleArray(WINDOW_SAMPLES)
        for (k in firstBin..lastBin) {
            var c = 0.0
            var s = 0.0
            for (t in 0 until WINDOW_SAMPLES) {
                val v = x[t] - mean
                c += v * cosTable[k][t]
                s += v * sinTable[k][t]
            }
            for (t in 0 until WINDOW_SAMPLES) out[t] += 2.0 / WINDOW_SAMPLES * (c * cosTable[k][t] + s * sinTable[k][t])
        }
        return out
    }

    /** The unit direction in x, y, z along which the band-passed motion varies most (power iteration on the covariance). */
    private fun mainDirection(b: Array<DoubleArray>): DoubleArray {
        val cov = Array(3) { i -> DoubleArray(3) { j -> (0 until WINDOW_SAMPLES).sumOf { b[i][it] * b[j][it] } } }
        var v = doubleArrayOf(1.0, 1.0, 1.0)
        repeat(50) {
            val next = DoubleArray(3) { i -> cov[i][0] * v[0] + cov[i][1] * v[1] + cov[i][2] * v[2] }
            val norm = sqrt(next.sumOf { it * it })
            if (norm == 0.0) return v
            v = DoubleArray(3) { next[it] / norm }
        }
        return v
    }
}
