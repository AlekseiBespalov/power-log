package app.powerlog.bridge

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

internal object MonitorRasterAxes {
    const val fontSize = 10.0
    private const val textHeight = 14.0
    private const val inset = 4.0
    private const val horizontalPadding = 4.0
    private const val verticalPadding = 2.0
    private const val gridOffset = 2.0
    private const val leftInset = 8.0
    private const val rightInset = 8.0

    data class Label(val x: Double, val y: Double, val width: Double, val height: Double) {
        val textX
            get() = x + horizontalPadding

        val textY
            get() = y + verticalPadding
    }

    fun yLabel(ordinate: Double, textWidth: Double, width: Double, height: Double): Label {
        val labelWidth = labelWidth(textWidth, width)
        val labelHeight = textHeight + verticalPadding * 2
        val top = 8 + inset
        val bottom = max(top, height - 24 - inset - labelHeight)
        return Label(leftInset + inset, min(bottom, max(top, ordinate + gridOffset)), labelWidth, labelHeight)
    }

    fun xLabel(fraction: Double, textWidth: Double, width: Double, height: Double): Label {
        val labelWidth = labelWidth(textWidth, width)
        val labelHeight = textHeight + verticalPadding * 2
        val left = leftInset + inset
        val right = width - rightInset - inset - labelWidth
        val x = leftInset + (width - leftInset - rightInset) * fraction - labelWidth / 2
        return Label(max(left, min(right, x)), height - inset - labelHeight, labelWidth, labelHeight)
    }

    private fun labelWidth(textWidth: Double, width: Double) =
        min(ceil(textWidth) + horizontalPadding * 2, max(0.0, width - leftInset - rightInset - inset * 2))

    fun draw(
        canvas: Canvas,
        paint: Paint,
        width: Double,
        height: Double,
        density: Float,
        low: Double,
        high: Double,
        decimals: Int,
        start: Double,
        end: Double,
        hasSamples: Boolean,
    ) {
        if (!hasSamples) return
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
        paint.textSize = (fontSize * density).toFloat()
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.textAlign = Paint.Align.LEFT
        fun drawLabel(text: String, label: Label) {
            paint.color = Color.rgb(11, 13, 16)
            canvas.drawRect(
                RectF(
                    (label.x * density).toFloat(),
                    (label.y * density).toFloat(),
                    ((label.x + label.width) * density).toFloat(),
                    ((label.y + label.height) * density).toFloat(),
                ),
                paint,
            )
            paint.color = Color.rgb(155, 166, 182)
            canvas.save()
            canvas.clipRect(
                (label.textX * density).toFloat(),
                (label.textY * density).toFloat(),
                ((label.x + label.width - horizontalPadding) * density).toFloat(),
                ((label.textY + textHeight) * density).toFloat(),
            )
            canvas.drawText(
                text,
                (label.textX * density).toFloat(),
                (label.textY * density - paint.fontMetrics.top).toFloat(),
                paint,
            )
            canvas.restore()
        }
        for (i in 0..2) {
            val fraction = i / 2.0
            val text = String.format(Locale.ROOT, "%.${decimals}f", low + fraction * (high - low))
            val ordinate = 8 + (1 - fraction) * (height - 34)
            drawLabel(text, yLabel(ordinate, paint.measureText(text) / density.toDouble(), width, height))
            val seconds = max(0, (start + (end - start) * fraction).toInt())
            val time =
                if (seconds >= 3600)
                    String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
            drawLabel(time, xLabel(fraction, paint.measureText(time) / density.toDouble(), width, height))
        }
    }
}
