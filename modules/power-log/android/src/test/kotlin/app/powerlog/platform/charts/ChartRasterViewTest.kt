package app.powerlog.platform.charts

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import expo.modules.core.ModuleRegistry
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.ModulesProvider
import expo.modules.kotlin.modules.Module
import java.lang.ref.WeakReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ChartRasterViewTest {
    private val application = RuntimeEnvironment.getApplication()

    private fun appContext(): AppContext {
        val reactContext =
            Class.forName("com.facebook.react.bridge.BridgeReactContext")
                .getConstructor(android.content.Context::class.java)
                .newInstance(application)
        return AppContext::class
            .java
            .declaredConstructors
            .single()
            .newInstance(
                object : ModulesProvider {
                    override fun getModulesMap(): Map<Class<out Module>, String?> = emptyMap()
                },
                ModuleRegistry(emptyList(), emptyList()),
                WeakReference(reactContext),
            ) as AppContext
    }

    @Test
    fun detachingCancelsAnInFlightRender() {
        val view = ChartRasterView(application, appContext())
        val generation = ChartRasterView::class.java.getDeclaredField("generation").apply { isAccessible = true }
        val before = generation.getLong(view)
        ChartRasterView::class.java.getDeclaredMethod("onDetachedFromWindow").apply { isAccessible = true }.invoke(view)
        assertTrue(generation.getLong(view) > before)
    }

    @Test
    fun everySelectionDrawingPathRejectsDelayedPackets() {
        val appContext = appContext()
        val series = ChartRasterScene.Series("humanPowerW", Color.RED, emptyList(), false)
        val sceneClass = Class.forName("app.powerlog.platform.charts.ChartRasterView\$Scene")
        val scene =
            sceneClass.declaredConstructors
                .single()
                .apply { isAccessible = true }
                .newInstance(
                    "key",
                    "source",
                    "R+1",
                    0.0,
                    10.0,
                    0.0,
                    100.0,
                    0,
                    listOf(series),
                    Bitmap.createBitmap(344, 116, Bitmap.Config.ARGB_8888),
                    360,
                    140,
                    1L,
                )
        val packet =
            JSONObject(
                """{"sourceId":"source","revision":"R+1","epoch":2,"sequence":3,"referenceS":7,"cursorPending":false,"points":[{"id":"humanPowerW","atS":3,"value":50}],"references":[{"id":"humanPowerW","atS":7,"value":50}],"tails":[{"id":"humanPowerW","startS":8,"endS":9,"value":50}]}"""
            )
        fun draw(): Pair<Int, Int> {
            val view = ChartRasterView(application, appContext)
            view.layout(0, 0, 360, 140)
            view.source("source")
            view.presentation(listOf(0.0, 10.0, 1.0, 3.0, 1.0, 7.0, 2.0, 3.0))
            ChartRasterView::class.java.getDeclaredField("accepted").apply { isAccessible = true }.set(view, scene)
            val canvas = Canvas(Bitmap.createBitmap(360, 140, Bitmap.Config.ARGB_8888))
            view.selection(packet.toString())
            ChartRasterView::class
                .java
                .getDeclaredMethod("onDraw", Canvas::class.java)
                .apply { isAccessible = true }
                .invoke(view, canvas)
            return shadowOf(canvas).circlePaintHistoryCount to shadowOf(canvas).linePaintHistoryCount
        }
        val accepted = draw()
        assertEquals(4, accepted.first)
        for ((field, value) in
            listOf("revision" to "R", "sourceId" to "other", "epoch" to 1, "sequence" to 2, "referenceS" to 6)) {
            val previous = packet.get(field)
            packet.put(field, value)
            val rejected = draw()
            assertEquals(field, 0, rejected.first)
            assertEquals(field, accepted.second - 1, rejected.second)
            packet.put(field, previous)
        }
    }
}
