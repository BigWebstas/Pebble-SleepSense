package net.webstas.sleepsense

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Turns a block of audio into [BANDS] log-spaced loudness values (0..1) from 40 Hz up. */
object Spectrum {
    const val BANDS = 48
    private const val MIN_HZ = 40.0
    private val window = HashMap<Int, FloatArray>()

    fun bands(samples: ShortArray, sampleRate: Int): FloatArray {
        val n = Integer.highestOneBit(samples.size) // an FFT wants a power of two
        val w = window.getOrPut(n) { FloatArray(n) { 0.5f - 0.5f * cos(2 * PI.toFloat() * it / (n - 1)) } }
        val re = FloatArray(n) { samples[it] / 32768f * w[it] }
        val im = FloatArray(n)
        fft(re, im)
        val maxHz = sampleRate / 2.0 * 0.95
        val binHz = sampleRate.toDouble() / n
        val out = FloatArray(BANDS)
        for (b in 0 until BANDS) {
            val lo = MIN_HZ * (maxHz / MIN_HZ).pow(b / BANDS.toDouble())
            val hi = MIN_HZ * (maxHz / MIN_HZ).pow((b + 1) / BANDS.toDouble())
            val from = (lo / binHz).toInt().coerceIn(1, n / 2 - 1)
            val to = max(from, min((hi / binHz).toInt(), n / 2 - 1))
            var sum = 0f
            for (k in from..to) sum += re[k] * re[k] + im[k] * im[k]
            val mag = sqrt(sum / (to - from + 1)) / (n / 4f)
            out[b] = ((20 * log10(mag + 1e-9f) + 80) / 70f).coerceIn(0f, 1f) // -80 dB..-10 dB
        }
        return out
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { re[i] = re[j].also { re[j] = re[i] }; im[i] = im[j].also { im[j] = im[i] } }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/** A scrolling spectrogram: time runs left to right (newest at the right), low pitch at the bottom. */
class SpectrogramView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val columns = 160
    private val bitmap = Bitmap.createBitmap(columns, Spectrum.BANDS, Bitmap.Config.ARGB_8888)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val low = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHighest)
    private val high = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
    private val pixels = IntArray(columns * Spectrum.BANDS)
    private val dst = Rect()
    private val tick = object : Runnable {
        override fun run() {
            WhiteNoisePlayer.latestBands()?.let { push(it) }
            postDelayed(this, 60)
        }
    }

    init { pixels.fill(low); bitmap.setPixels(pixels, 0, columns, 0, 0, columns, Spectrum.BANDS) }

    /** Adds one column of [bands] on the right, dropping the oldest on the left. */
    fun push(bands: FloatArray) {
        for (y in 0 until Spectrum.BANDS) {
            val row = y * columns
            System.arraycopy(pixels, row + 1, pixels, row, columns - 1)
            pixels[row + columns - 1] = blend(bands[Spectrum.BANDS - 1 - y])
        }
        bitmap.setPixels(pixels, 0, columns, 0, 0, columns, Spectrum.BANDS)
        invalidate()
    }

    fun clear() {
        pixels.fill(low)
        bitmap.setPixels(pixels, 0, columns, 0, 0, columns, Spectrum.BANDS)
        invalidate()
    }

    private fun blend(t: Float): Int {
        val u = t * t // keeps quiet bands dark so the loud ones stand out
        return Color.rgb(
            (Color.red(low) + (Color.red(high) - Color.red(low)) * u).toInt(),
            (Color.green(low) + (Color.green(high) - Color.green(low)) * u).toInt(),
            (Color.blue(low) + (Color.blue(high) - Color.blue(low)) * u).toInt(),
        )
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        dst.set(0, 0, width, height)
        canvas.drawBitmap(bitmap, null, dst, paint)
    }
}
