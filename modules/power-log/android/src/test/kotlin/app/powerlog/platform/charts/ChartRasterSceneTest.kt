package app.powerlog.platform.charts

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ChartRasterSceneTest {
    private val fixture =
        JSONObject(
            File(
                    System.getenv("POWER_LOG_CONTRACT_FIXTURES") ?: "../../../tests/fixtures/contract",
                    "charts/raster.json",
                )
                .readText()
        )

    @Test
    fun decodesTheSharedSceneSelectionAndTarget() {
        val source = fixture.getString("source")
        val scene = ChartRasterScene.parse(fixture.getJSONObject("scene").toString(), source)
        assertEquals("fixture-scene", scene.key)
        assertEquals("7", scene.revision)
        assertEquals(listOf(0.0, 10.0, 0.0, 100.0), listOf(scene.start, scene.end, scene.min, scene.max))
        assertEquals(listOf("humanPowerW" to 3, "assistLevel" to 2), scene.series.map { it.id to it.points.size })
        assertEquals(listOf(false, true), scene.series.map { it.step })

        val selection = ChartSelection.parse(fixture.getJSONObject("selection").toString())!!
        assertEquals(listOf(5.0), selection.points.map { it.atS })
        assertEquals(listOf(0.0), selection.references.map { it.atS })
        assertEquals(listOf(ChartSelection.Tail("humanPowerW", 10.0, 12.0, 55.0)), selection.tails)
        assertEquals(2.0, selection.referenceS!!, 0.0)

        val target = ChartTarget.parse(fixture.getJSONObject("target").toString())!!
        assertEquals(listOf("view:1"), target.points!!.map { it.key })
    }

    @Test
    fun rejectsEverySharedBrokenScene() {
        val source = fixture.getString("source")
        val rejected = fixture.getJSONArray("rejected")
        for (index in 0 until rejected.length()) {
            val case = rejected.getJSONObject(index)
            val refused = runCatching { ChartRasterScene.parse(case.getJSONObject("scene").toString(), source) }
            assertTrue(case.getString("case"), refused.isFailure)
        }
    }
}
