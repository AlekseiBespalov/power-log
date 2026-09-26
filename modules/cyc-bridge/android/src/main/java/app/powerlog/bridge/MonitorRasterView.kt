package app.powerlog.bridge

import android.content.Context
import android.graphics.*
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import java.util.concurrent.Executors
import kotlin.math.*
import org.json.JSONObject

/** Geometry is rasterized off the UI thread; gestures only transform the accepted bitmap. */
internal class MonitorRasterView(context: Context, appContext: AppContext) :
    ExpoView(context, appContext) {
    val onRenderStatus by EventDispatcher()
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val viewId = java.util.UUID.randomUUID().toString()
    @Volatile private var generation = 0L
    private var source = ""
    private var sceneKey = ""
    private var sceneJson = ""
    private var selection = JSONObject()
    private var target = JSONObject()
    private var presentation = emptyList<Double>()

    private data class Point(val time: Double, val value: Double, val start: Boolean)

    private data class Series(
        val id: String,
        val color: Int,
        val points: List<Point>,
        val step: Boolean,
    )

    private data class Scene(
        val key: String,
        val source: String,
        val start: Double,
        val end: Double,
        val min: Double,
        val max: Double,
        val decimals: Int,
        val series: List<Series>,
        val bitmap: Bitmap,
        val width: Int,
        val height: Int,
        val generation: Long,
    )

    private var accepted: Scene? = null
    private val markers = MonitorMarkers()

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
        selection = runCatching { JSONObject(value) }.getOrDefault(JSONObject())
        invalidate()
    }

    fun target(value: String) {
        target = runCatching { JSONObject(value) }.getOrDefault(JSONObject())
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
                require(json.length <= 2 * 1024 * 1024)
                val data = JSONObject(json)
                val key = data.getString("key")
                require(data.getString("sourceId") == expectedSource)
                val start = data.getDouble("start")
                val end = data.getDouble("end")
                val low = data.getDouble("min")
                val high = data.getDouble("max")
                require(
                    listOf(start, end, low, high).all { it.isFinite() } && end > start && high > low
                )
                val lanes = data.optInt("laneCount", 1).coerceIn(1, 128)
                val plotWidth = w - 16 * density
                val plotHeight = h - 24 * density
                val scale =
                    min(
                        1.0,
                        sqrt(
                            min(3 * 1024 * 1024.0, 32 * 1024 * 1024.0 / lanes) /
                                (plotWidth * plotHeight * 4)
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
                val array = data.getJSONArray("series")
                require(array.length() <= 32)
                var total = 0
                val series =
                    (0 until array.length()).map { i ->
                        val item = array.getJSONObject(i)
                        val rows = item.getJSONArray("points")
                        total += rows.length()
                        require(total <= 16384)
                        val points =
                            (0 until rows.length()).map { j ->
                                val p = rows.getJSONArray(j)
                                Point(p.getDouble(0), p.getDouble(1), p.getBoolean(2))
                            }
                        require(
                            points.all { it.time.isFinite() && it.value.isFinite() } &&
                                points.zipWithNext().all { (a, b) -> a.time <= b.time }
                        )
                        val path = Path()
                        var previous: Point? = null
                        points.forEach { p ->
                            val x = ((p.time - start) / (end - start) * bw).toFloat()
                            val y =
                                ((8 * density +
                                        (high - p.value) / (high - low) * (h - 34 * density)) * bh /
                                        plotHeight)
                                    .toFloat()
                            if (previous == null || p.start) path.moveTo(x, y)
                            else {
                                if (item.optBoolean("step"))
                                    path.lineTo(
                                        x,
                                        ((8 * density +
                                                (high - previous!!.value) / (high - low) *
                                                    (h - 34 * density)) * bh / plotHeight)
                                            .toFloat(),
                                    )
                                path.lineTo(x, y)
                            }
                            previous = p
                        }
                        val color = Color.parseColor(item.getString("color"))
                        brush.color = color
                        canvas.drawPath(path, brush)
                        brush.style = Paint.Style.FILL
                        points.forEachIndexed { index, p ->
                            if (
                                (index == 0 || p.start) &&
                                    (index == points.lastIndex || points[index + 1].start)
                            ) {
                                canvas.drawCircle(
                                    ((p.time - start) / (end - start) * bw).toFloat(),
                                    ((8 * density +
                                            (high - p.value) / (high - low) * (h - 34 * density)) *
                                            bh / plotHeight)
                                        .toFloat(),
                                    (1.8 * density * scale).toFloat(),
                                    brush,
                                )
                            }
                        }
                        brush.style = Paint.Style.STROKE
                        Series(item.getString("id"), color, points, item.optBoolean("step"))
                    }
                val frame =
                    Scene(
                        key,
                        expectedSource,
                        start,
                        end,
                        low,
                        high,
                        data.optInt("decimals", 0).coerceIn(0, 8),
                        series,
                        bitmap,
                        w,
                        h,
                        version,
                    )
                post {
                    if (
                        version != generation ||
                            source != expectedSource ||
                            width != w ||
                            height != h
                    ) {
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
        fun x(t: Double) =
            (left + (t - viewStart) / (viewEnd - viewStart) * (right - left)).toFloat()
        fun y(v: Double) =
            (top + (scene.max - v) / (scene.max - scene.min) * (bottom - top)).toFloat()
        paint.strokeWidth = density
        paint.color = Color.rgb(48, 56, 68)
        paint.style = Paint.Style.STROKE
        paint.pathEffect = DashPathEffect(floatArrayOf(2 * density, 5 * density), 0f)
        for (i in 0..2) canvas.drawLine(
            left,
            top + (bottom - top) * i / 2,
            right,
            top + (bottom - top) * i / 2,
            paint,
        )
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
        paint.textSize = 10 * density
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.color = Color.rgb(155, 166, 182)
        if (scene.series.any { it.points.isNotEmpty() })
            for (i in 0..2) {
                paint.textAlign = Paint.Align.LEFT
                canvas.drawText(
                    String.format(
                        java.util.Locale.ROOT,
                        "%.${scene.decimals}f",
                        scene.max - (scene.max - scene.min) * i / 2,
                    ),
                    left + 4 * density,
                    top + (bottom - top) * i / 2 + 2 * density - paint.fontMetrics.top,
                    paint,
                )
                val t = viewStart + (viewEnd - viewStart) * i / 2
                val seconds = max(0, t.toInt())
                val text =
                    if (seconds >= 3600)
                        "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
                    else "%02d:%02d".format(seconds / 60, seconds % 60)
                paint.textAlign =
                    when (i) {
                        0 -> Paint.Align.LEFT
                        1 -> Paint.Align.CENTER
                        else -> Paint.Align.RIGHT
                    }
                canvas.drawText(
                    text,
                    left + (right - left) * i / 2,
                    height - 14 * density - paint.fontMetrics.top,
                    paint,
                )
            }
        canvas.save()
        canvas.clipRect(left, 0f, right, height - 24 * density)
        paint.color = Color.WHITE
        canvas.drawBitmap(
            scene.bitmap,
            null,
            RectF(x(scene.start), 0f, x(scene.end), height - 24 * density),
            paint,
        )
        if (selection.optString("sourceId") == source) {
            val tails = selection.optJSONArray("tails")
            if (tails != null)
                for (i in 0 until min(32, tails.length())) {
                    val tail = tails.getJSONObject(i)
                    val series = scene.series.find { it.id == tail.optString("id") } ?: continue
                    val a = tail.optDouble("start")
                    val b = tail.optDouble("end")
                    val value = tail.optDouble("value")
                    if (a.isFinite() && b.isFinite() && value.isFinite() && b >= a) {
                        paint.color = series.color
                        paint.strokeWidth = 1.8f * density
                        canvas.drawLine(
                            x(max(a, viewStart)),
                            y(value),
                            x(min(b, viewEnd)),
                            y(value),
                            paint,
                        )
                    }
                }
        }
        paint.strokeWidth = density
        val cursor = presentation.takeIf { it.size == 8 && it[2] == 1.0 }?.get(3)
        val reference = presentation.takeIf { it.size == 8 && it[4] == 1.0 }?.get(5)
        if (reference != null) {
            paint.color = Color.rgb(155, 166, 182)
            paint.pathEffect = DashPathEffect(floatArrayOf(3 * density, 4 * density), 0f)
            canvas.drawLine(x(reference), 4 * density, x(reference), bottom, paint)
            paint.pathEffect = null
        }
        if (cursor != null) {
            paint.color = Color.rgb(244, 246, 250)
            canvas.drawLine(x(cursor), 4 * density, x(cursor), bottom, paint)
        }
        markers.synchronize(source, presentation)
        markers.receive(selection, scene.series.map { it.id }.toSet())
        markers.target(target)
        fun drawPoint(point: MonitorMarkers.Point, reference: Boolean = false) {
            if (point.seconds !in viewStart..viewEnd) return
            val series = scene.series.find { it.id == point.id } ?: return
            paint.style = Paint.Style.FILL
            paint.color = if (reference) Color.rgb(21, 25, 31) else series.color
            canvas.drawCircle(x(point.seconds), y(point.value), 4 * density, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (if (reference) 1.5f else 2f) * density
            paint.color = if (reference) series.color else Color.rgb(21, 25, 31)
            canvas.drawCircle(x(point.seconds), y(point.value), 4 * density, paint)
            paint.style = Paint.Style.FILL
        }
        markers.points().forEach { drawPoint(it) }
        markers.primary("$viewId:${scene.generation}")?.let { point ->
            val series = scene.series.find { it.id == point.id }
            var low = 0
            var high = series?.points?.size ?: 0
            while (low < high) {
                val mid = (low + high) / 2
                if (series!!.points[mid].time < point.seconds) low = mid + 1 else high = mid
            }
            while (
                series != null &&
                    low < series.points.size &&
                    series.points[low].time == point.seconds
            ) {
                if (series.points[low++].value == point.value) {
                    drawPoint(point)
                    break
                }
            }
        }
        if (
            reference != null &&
                selection.optString("sourceId") == source &&
                selection.optDouble("epoch") == presentation[6] &&
                selection.optDouble("referenceSeconds") == reference
        ) {
            selection.optJSONArray("references")?.let { points ->
                for (i in 0 until minOf(32, points.length())) MonitorMarkers.parse(
                        points.getJSONObject(i)
                    )
                    ?.let { drawPoint(it, true) }
            }
        }
        canvas.restore()
    }
}
