package net.webstas.sleepsense

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import org.json.JSONObject

/** One night as a thin bar of colored stage segments (awake, light, deep, REM), from the session's samples. */
class StageRibbonView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private class Segment(val from: Float, val to: Float, val stage: Int)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private var segments = emptyList<Segment>()

    fun set(session: JSONObject?) {
        val samples = session?.optJSONArray("samples")
        val start = session?.optLong("start") ?: 0L
        val end = session?.optLong("end") ?: 0L
        segments = if (samples == null || samples.length() == 0 || end <= start) emptyList() else {
            val span = (end - start).toFloat()
            (0 until samples.length()).map { i ->
                val sample = samples.getJSONObject(i)
                val from = sample.optLong("t")
                val to = if (i + 1 < samples.length()) samples.getJSONObject(i + 1).optLong("t") else end
                Segment(((from - start) / span).coerceIn(0f, 1f), ((to - start) / span).coerceIn(0f, 1f), sample.optInt("s"))
            }
        }
        invalidate()
    }

    private fun color(stage: Int) = ContextCompat.getColor(
        context,
        when (stage) {
            1 -> R.color.stage_light
            2 -> R.color.stage_deep
            3 -> R.color.stage_rem
            else -> R.color.stage_awake
        },
    )

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        clip.reset()
        clip.addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, Path.Direction.CW)
        canvas.clipPath(clip)
        for (s in segments) {
            paint.color = color(s.stage)
            canvas.drawRect(s.from * width, 0f, s.to * width, height.toFloat(), paint)
        }
    }
}
