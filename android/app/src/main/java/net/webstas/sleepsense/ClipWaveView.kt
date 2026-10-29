package net.webstas.sleepsense

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.max

private const val COLUMNS = 160
private const val WAV_HEADER = 44L

/** A noise clip's loudness over time: one bar per slice, the loudest slice picked out. */
class ClipWaveView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val bar = Paint().apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = density * 2
        color = MaterialColors.getColor(this@ClipWaveView, androidx.appcompat.R.attr.colorPrimary)
    }
    private val peakBar = Paint().apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = density * 3
        color = MaterialColors.getColor(this@ClipWaveView, androidx.appcompat.R.attr.colorError)
    }
    private val grid = Paint().apply {
        strokeWidth = density
        color = MaterialColors.getColor(this@ClipWaveView, com.google.android.material.R.attr.colorOutlineVariant)
    }
    private var levels = FloatArray(0) // 0..1 of the clip's loudest sample
    private var loudest = -1

    /** Reads the clip off the main thread; the view stays empty until it is done. */
    fun load(file: File) {
        levels = FloatArray(0)
        invalidate()
        Thread {
            val read = runCatching { envelope(file) }.getOrNull() ?: return@Thread
            post {
                levels = read
                loudest = read.indices.maxByOrNull { read[it] } ?: -1
                invalidate()
            }
        }.start()
    }

    // The largest sample in each of COLUMNS equal slices of the 16-bit mono PCM after the WAV header
    private fun envelope(file: File): FloatArray {
        val samples = ((file.length() - WAV_HEADER) / 2).toInt()
        if (samples < COLUMNS) return FloatArray(0)
        val peaks = IntArray(COLUMNS)
        RandomAccessFile(file, "r").use { f ->
            val bytes = ByteArray(samples * 2)
            f.seek(WAV_HEADER)
            f.readFully(bytes)
            for (i in 0 until samples) {
                val s = (bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)
                val col = (i.toLong() * COLUMNS / samples).toInt()
                peaks[col] = max(peaks[col], abs(s))
            }
        }
        val top = max(peaks.max(), 1).toFloat()
        return FloatArray(COLUMNS) { peaks[it] / top }
    }

    override fun onDraw(canvas: Canvas) {
        val mid = height / 2f
        canvas.drawLine(0f, mid, width.toFloat(), mid, grid)
        if (levels.isEmpty()) return
        val step = width.toFloat() / levels.size
        val reach = mid - density * 2
        for (i in levels.indices) {
            val x = step * (i + 0.5f)
            val half = max(levels[i] * reach, density)
            canvas.drawLine(x, mid - half, x, mid + half, if (i == loudest) peakBar else bar)
        }
    }
}
