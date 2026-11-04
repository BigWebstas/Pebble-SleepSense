package net.webstas.sleepsense

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

/** How loud a noise clip is over its length: [BARS] bars of level (0 to 1), with a playhead while it plays. */
class ClipLevelView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    companion object {
        const val BARS = 72
        private const val FLOOR_DB = -60f // level 0 is this quiet, level 1 is full scale

        /** The level per bar of a clip written by NoiseMonitor (44 byte header, 16-bit mono), as RMS in dB. */
        fun levels(file: File): FloatArray {
            val bytes = file.readBytes()
            val samples = ((bytes.size - 44) / 2).coerceAtLeast(0)
            val out = FloatArray(BARS)
            if (samples == 0) return out
            for (bar in 0 until BARS) {
                val from = (samples.toLong() * bar / BARS).toInt()
                val to = (samples.toLong() * (bar + 1) / BARS).toInt().coerceAtLeast(from + 1).coerceAtMost(samples)
                var sum = 0.0
                for (i in from until to) {
                    val at = 44 + i * 2
                    val v = ((bytes[at + 1].toInt() shl 8) or (bytes[at].toInt() and 0xFF)).toShort().toInt()
                    sum += v.toDouble() * v
                }
                val rms = sqrt(sum / (to - from)) / 32768.0
                val db = if (rms <= 0.0) FLOOR_DB else (20 * log10(rms)).toFloat()
                out[bar] = ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
            }
            return out
        }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = RectF()
    private val idle = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
    private val played = MaterialColors.getColor(this, com.google.android.material.R.attr.colorTertiary)
    private val track = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)

    var levels: FloatArray? = null
        set(value) {
            field = value
            invalidate()
        }

    /** How far playback has come, 0 to 1; negative when nothing is playing. */
    var progress = -1f
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val h = levels
        val slot = width.toFloat() / BARS
        val gap = slot * 0.3f
        val mid = height / 2f
        for (i in 0 until BARS) {
            val level = h?.get(i) ?: 0f
            val half = maxOf(level * mid, 2f)
            val x = i * slot
            paint.color = when {
                h == null -> track
                progress >= 0f && (i + 0.5f) / BARS <= progress -> played
                else -> idle
            }
            bar.set(x + gap / 2, mid - half, x + slot - gap / 2, mid + half)
            canvas.drawRoundRect(bar, slot / 2, slot / 2, paint)
        }
    }
}
