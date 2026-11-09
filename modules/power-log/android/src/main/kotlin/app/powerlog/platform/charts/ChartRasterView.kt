package app.powerlog.platform.charts

import android.content.Context
import android.graphics.*
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import java.util.concurrent.Executors
import kotlin.math.*

internal class ChartRasterView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {
    val onRenderStatus by EventDispatcher()
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val viewId = java.util.UUID.randomUUID().toString()
    @Volatile private var generation = 0L
    private var source = ""
    private var sceneKey = ""
    private var sceneJson = ""
    private var selection: ChartSelection? = null
    private var target: ChartTarget? = null
    private val gridDash = DashPathEffect(floatArrayOf(2 * density, 5 * density), 0f)
    private val referenceDash = DashPathEffect(floatArrayOf(3 * density, 4 * density), 0f)
    private val bitmapBounds = RectF()
    private var presentation = emptyList<Double>()

    private data class Scene(
        val key: String,
        val source: String,
        val revision: String,
        val start: Double,
        val end: Double,
        val min: Double,
        val max: Double,
        val decimals: Int,
        val series: List<ChartRasterScene.Series>,
        val bitmap: Bitmap,
        val width: Int,
        val height: Int,
        val generation: Long,
    ) {
        val ids: Set<String> = series.mapTo(HashSet()) { it.id }
    }

    private var accepted: Scene? = null
    private val markers = ChartRasterMarkers()

    init {
        setWillNotDraw(false)
    }

    fun source(value: String) {
        if (source != value) {
            source = value
            accepted = null
            generation++
            invalidate()
            render()
        }
    }

    fun key(value: String) {
        sceneKey = value
    }

    fun scene(value: String) {
        if (value != sceneJson) {
            sceneJson = value
            render()
        }
    }

    fun selection(value: String) {
        selection = ChartSelection.parse(value)
        invalidate()
    }

    fun target(value: String) {
        target = ChartTarget.parse(value)
        invalidate()
    }

    fun presentation(value: List<Double>) {
        if (
            value.size == 8 &&
                value.all { it.isFinite() } &&
                value[1] > value[0] &&
                (presentation.size != 8 ||
                    value[6] > presentation[6] ||
                    value[6] == presentation[6] && value[7] >= presentation[7])
        ) {
            presentation = value
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        render()
    }

    override fun onDetachedFromWindow() {
        generation++
        accepted = null
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        render()
    }

    private fun render() {
        val w = width
        val h = height
        val json = sceneJson
        val expectedSource = source
        if (w <= 16 * density || h <= 34 * density || json.isBlank()) return
        val version = ++generation
        worker.execute {
            if (version != generation) return@execute
            try {
                val parsed = ChartRasterScene.parse(json, expectedSource)
                val start = parsed.start
                val end = parsed.end
                val low = parsed.min
                val high = parsed.max
                val plotWidth = w - 16 * density
                val plotHeight = h - 24 * density
                val scale =
                    min(
                        1.0,
                        sqrt(
                            min(3 * 1024 * 1024.0, 32 * 1024 * 1024.0 / parsed.laneCount) / (plotWidth * plotHeight * 4)
                        ),
                    )
                val bw = max(1, (plotWidth * scale).toInt())
                val bh = max(1, (plotHeight * scale).toInt())
                val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val brush =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = (1.8 * density * scale).toFloat()
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                    }
                fun x(time: Double) = ((time - start) / (end - start) * bw).toFloat()
                fun y(value: Double) =
                    ((8 * density + (high - value) / (high - low) * (h - 34 * density)) * bh / plotHeight).toFloat()
                for (series in parsed.series) {
                    val points = series.points
                    val path = Path()
                    var previous: ChartRasterScene.Point? = null
                    points.forEach { p ->
                        if (previous == null || p.start) path.moveTo(x(p.time), y(p.value))
                        else {
                            if (series.step) path.lineTo(x(p.time), y(previous!!.value))
                            path.lineTo(x(p.time), y(p.value))
                        }
                        previous = p
                    }
                    brush.color = series.color
                    canvas.drawPath(path, brush)
                    brush.style = Paint.Style.FILL
                    points.forEachIndexed { index, p ->
                        if ((index == 0 || p.start) && (index == points.lastIndex || points[index + 1].start))
                            canvas.drawCircle(x(p.time), y(p.value), (1.8 * density * scale).toFloat(), brush)
                    }
                    brush.style = Paint.Style.STROKE
                }
                val key = parsed.key
                val frame =
                    Scene(
                        key,
                        expectedSource,
                        parsed.revision,
                        start,
                        end,
                        low,
                        high,
                        parsed.decimals,
                        parsed.series,
                        bitmap,
                        w,
                        h,
                        version,
                    )
                post {
                    if (version != generation || width != w || height != h) {
                        bitmap.recycle()
                        return@post
                    }
                    accepted = frame
                    invalidate()
                    onRenderStatus(
                        mapOf(
                            "status" to "ready",
                            "key" to key,
                            "sourceId" to expectedSource,
                            "acceptanceGeneration" to version,
                            "viewId" to viewId,
                            "acceptanceId" to "$viewId:$version",
                            "width" to w / density,
                            "height" to h / density,
                            "displayScale" to density,
                        )
                    )
                }
            } catch (error: Exception) {
                post {
                    if (version == generation)
                        onRenderStatus(
                            mapOf(
                                "status" to "error",
                                "key" to sceneKey,
                                "message" to "Chart drawing unavailable",
                            )
                        )
                }
            }
        }
    }

    companion object {
        private val worker = Executors.newSingleThreadExecutor()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scene = accepted ?: return
        val left = 8 * density
        val right = width - 8 * density
        val top = 8 * density
        val bottom = height - 26 * density
        val viewStart = presentation.getOrNull(0) ?: scene.start
        val viewEnd = presentation.getOrNull(1) ?: scene.end
        fun x(t: Double) = (left + (t - viewStart) / (viewEnd - viewStart) * (right - left)).toFloat()
        fun y(v: Double) = (top + (scene.max - v) / (scene.max - scene.min) * (bottom - top)).toFloat()
        paint.strokeWidth = density
        paint.color = Color.rgb(48, 56, 68)
        paint.style = Paint.Style.STROKE
        paint.pathEffect = gridDash
        for (i in 0..2) canvas.drawLine(
            left,
            top + (bottom - top) * i / 2,
            right,
            top + (bottom - top) * i / 2,
            paint,
        )
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
        canvas.save()
        canvas.clipRect(left, 0f, right, height - 24 * density)
        paint.color = Color.WHITE
        bitmapBounds.set(x(scene.start), 0f, x(scene.end), height - 24 * density)
        canvas.drawBitmap(scene.bitmap, null, bitmapBounds, paint)
        val reference = presentation.takeIf { it.size == 8 && it[4] == 1.0 }?.get(5)
        val packet = selection
        val shown = packet?.takeIf {
            presentation.size == 8 &&
                ChartRasterMarkers.acceptsPacket(source, scene.revision, presentation, it, reference)
        }
        if (shown != null)
            for (tail in shown.tails) {
                val series = scene.series.find { it.id == tail.id } ?: continue
                paint.color = series.color
                paint.strokeWidth = 1.8f * density
                canvas.drawLine(
                    x(max(tail.startS, viewStart)),
                    y(tail.value),
                    x(min(tail.endS, viewEnd)),
                    y(tail.value),
                    paint,
                )
            }
        paint.strokeWidth = density
        val cursor = presentation.takeIf { it.size == 8 && it[2] == 1.0 }?.get(3)
        if (reference != null) {
            paint.color = Color.rgb(155, 166, 182)
            paint.pathEffect = referenceDash
            canvas.drawLine(x(reference), 4 * density, x(reference), bottom, paint)
            paint.pathEffect = null
        }
        if (cursor != null) {
            paint.color = Color.rgb(244, 246, 250)
            canvas.drawLine(x(cursor), 4 * density, x(cursor), bottom, paint)
        }
        markers.synchronize(source, presentation, scene.revision)
        if (shown != null) markers.receive(shown, scene.ids)
        target?.let(markers::target)
        fun drawPoint(point: ChartRasterMarkers.Point, reference: Boolean = false) {
            if (point.atS !in viewStart..viewEnd) return
            val series = scene.series.find { it.id == point.id } ?: return
            paint.style = Paint.Style.FILL
            paint.color = if (reference) Color.rgb(21, 25, 31) else series.color
            canvas.drawCircle(x(point.atS), y(point.value), 4 * density, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (if (reference) 1.5f else 2f) * density
            paint.color = if (reference) series.color else Color.rgb(21, 25, 31)
            canvas.drawCircle(x(point.atS), y(point.value), 4 * density, paint)
            paint.style = Paint.Style.FILL
        }
        markers.points().forEach { drawPoint(it) }
        markers.previews("$viewId:${scene.generation}").forEach { point ->
            val series = scene.series.find { it.id == point.id }
            var low = 0
            var high = series?.points?.size ?: 0
            while (low < high) {
                val mid = (low + high) / 2
                if (series!!.points[mid].time < point.atS) low = mid + 1 else high = mid
            }
            while (series != null && low < series.points.size && series.points[low].time == point.atS) {
                if (series.points[low++].value == point.value) {
                    drawPoint(point)
                    break
                }
            }
        }
        if (reference != null && shown != null) shown.references.forEach { drawPoint(it, true) }
        canvas.restore()
        ChartRasterAxes.draw(
            canvas,
            paint,
            width / density.toDouble(),
            height / density.toDouble(),
            density,
            scene.min,
            scene.max,
            scene.decimals,
            viewStart,
            viewEnd,
            scene.series.any { it.points.isNotEmpty() },
        )
    }
}
