package net.webstas.sleepsense

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors

private const val WINDOW_MS = 6_000L
private const val DELAY_MS = 300L // drawn a little behind real time, so the bursty arrivals never leave a gap at the edge
private const val MAX_VALUE = 127f // the oximeter scales its own waveform to 0..127

/** Draws the oximeter's recent pulse waveform from [LiveReadings], scrolling with the clock at the display's frame rate. */
class WaveformView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = MaterialColors.getColor(this@WaveformView, androidx.appcompat.R.attr.colorPrimary)
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val end = System.currentTimeMillis() - DELAY_MS
        val start = end - WINDOW_MS
        val wave = LiveReadings.waveform(start, end) ?: return // asks again only once the activity sees data
        if (wave.times.size >= 2) {
            val pad = paint.strokeWidth
            val h = height - 2 * pad
            path.rewind()
            for (i in wave.times.indices) {
                val x = width * (wave.times[i] - start) / WINDOW_MS.toFloat()
                val y = pad + h * (1 - wave.values[i] / MAX_VALUE)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }
        postInvalidateOnAnimation() // the next frame, for as long as there is something to scroll
    }
}
