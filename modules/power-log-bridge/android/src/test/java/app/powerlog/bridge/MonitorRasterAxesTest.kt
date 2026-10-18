package app.powerlog.bridge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MonitorRasterAxesTest {
    @Test
    fun labelsMatchIPhoneInsetsAndKeepPlotGeometry() {
        for ((ordinate, y) in listOf(8.0 to 12.0, 61.0 to 63.0, 114.0 to 94.0)) {
            val label = MonitorRasterAxes.yLabel(ordinate, 28.0, 360.0, 140.0)
            assertEquals(MonitorRasterAxes.Label(12.0, y, 36.0, 18.0), label)
            assertEquals(16.0, label.textX, 0.0)
            assertEquals(y + 2, label.textY, 0.0)
            assertTrue(label.y + label.height <= 140 - 24 - 4)
        }
        for ((fraction, x) in listOf(0.0 to 12.0, 0.5 to 162.0, 1.0 to 312.0)) assertEquals(
            MonitorRasterAxes.Label(x, 118.0, 36.0, 18.0),
            MonitorRasterAxes.xLabel(fraction, 28.0, 360.0, 140.0),
        )
        val long = MonitorRasterAxes.yLabel(61.0, 800.0, 360.0, 140.0)
        assertEquals(348.0, long.x + long.width, 0.0)
    }

    private fun draw(density: Float, hasSamples: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap((360 * density).toInt(), (140 * density).toInt(), Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        MonitorRasterAxes.draw(
            Canvas(bitmap),
            Paint(Paint.ANTI_ALIAS_FLAG),
            360.0,
            140.0,
            density,
            0.0,
            100.0,
            0,
            0.0,
            10.0,
            hasSamples,
        )
        return bitmap
    }

    @Test
    fun opaqueCardBackingsCoverLinesAtEveryDensity() {
        for (density in listOf(1f, 2f, 3f)) {
            val bitmap = draw(density, true)
            val background = Color.rgb(11, 13, 16)
            for (y in listOf(12, 63, 94, 118)) assertEquals(
                background,
                bitmap.getPixel((13 * density).toInt(), ((y + 1) * density).toInt()),
            )
            assertEquals(Color.RED, bitmap.getPixel((8 * density).toInt(), (61 * density).toInt()))
            assertEquals(Color.RED, bitmap.getPixel((100 * density).toInt(), (61 * density).toInt()))
            assertEquals(Color.RED, bitmap.getPixel((13 * density).toInt(), (138 * density).toInt()))
            assertTrue(
                (14..28).any { y ->
                    (16..40).any { x ->
                        Color.green(bitmap.getPixel((x * density).toInt(), (y * density).toInt())) > 100
                    }
                }
            )
        }
    }

    @Test
    fun emptyChartsDrawNeitherAxisNumbersNorBackings() {
        val bitmap = draw(1f, false)
        assertTrue(
            (0 until bitmap.height).all { y -> (0 until bitmap.width).all { x -> bitmap.getPixel(x, y) == Color.RED } }
        )
    }
}
