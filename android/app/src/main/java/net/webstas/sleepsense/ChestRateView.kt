package net.webstas.sleepsense

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors

private const val LOW = 6f // breaths a minute at the bottom and top of the graph
private const val HIGH = 30f

/** Breathing rate over the chest test from [ChestCapture.rates]: a line with a break wherever there was no reading. */
class ChestRateView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 2
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = MaterialColors.getColor(this@ChestRateView, androidx.appcompat.R.attr.colorPrimary)
    }
    private val grid = Paint().apply {
        strokeWidth = density
        color = MaterialColors.getColor(this@ChestRateView, com.google.android.material.R.attr.colorOutlineVariant)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = density * 11
        color = MaterialColors.getColor(this@ChestRateView, com.google.android.material.R.attr.colorOnSurfaceVariant)
    }
    private val path = Path()
    private var rates: List<Pair<Long, Double?>> = emptyList()

    fun setRates(newRates: List<Pair<Long, Double?>>) {
        rates = newRates
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val left = density * 28 // room for the labels
        val top = density * 6
        val bottom = height - density * 6
        fun y(rate: Float) = bottom - (rate.coerceIn(LOW, HIGH) - LOW) / (HIGH - LOW) * (bottom - top)
        for (mark in intArrayOf(10, 20, 30)) {
            canvas.drawLine(left, y(mark.toFloat()), width.toFloat(), y(mark.toFloat()), grid)
            canvas.drawText(mark.toString(), 0f, y(mark.toFloat()) + label.textSize / 3, label)
        }
        if (rates.size < 2) return
        val start = rates.first().first
        val span = (rates.last().first - start).coerceAtLeast(1L).toFloat()
        path.rewind()
        var penDown = false
        for ((at, rate) in rates) {
            if (rate == null) {
                penDown = false
                continue
            }
            val x = left + (width - left) * (at - start) / span
            if (penDown) path.lineTo(x, y(rate.toFloat())) else path.moveTo(x, y(rate.toFloat()))
            penDown = true
        }
        canvas.drawPath(path, line)
    }
}
