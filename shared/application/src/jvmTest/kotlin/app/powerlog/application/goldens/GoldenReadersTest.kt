package app.powerlog.application.goldens

import app.powerlog.domain.core.analysis.distance.DistanceSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoldenReadersTest {
    @Test
    fun quotedCellsKeepTheirCommasQuotesAndNewlines() {
        val table = CsvTable("t.csv", "a,b,c\n1,\"x, \"\"y\"\"\nz\",\n,,last\n")
        val rows = table.rows().toList()
        assertEquals(listOf("a", "b", "c"), table.header)
        assertEquals(listOf(2, 3), rows.map { it.line })
        assertEquals("x, \"y\"\nz", rows[0].text("b"))
        assertNull(rows[0].textOrNull("c"))
        assertNull(rows[1].textOrNull("a"))
        assertEquals("last", rows[1].text("c"))
    }

    @Test
    fun aRecordWithAnotherCellCountOrAnOpenQuoteIsRefusedByLine() {
        assertEquals(
            "t.csv line 3 has 1 cells for 2 columns",
            assertFailsWith<GoldenRefusal> { CsvTable("t.csv", "a,b\n1,2\n3\n").rows().toList() }.message,
        )
        assertEquals(
            "t.csv ends inside a quoted cell",
            assertFailsWith<GoldenRefusal> { CsvTable("t.csv", "a\n\"open\n").rows().toList() }.message,
        )
        assertEquals(
            "t.csv has text after a quote",
            assertFailsWith<GoldenRefusal> { CsvTable("t.csv", "a\n\"x\"y\n").rows().toList() }.message,
        )
    }

    @Test
    fun columnsNoStoredColumnHoldsAndMissingColumnsAreRefused() {
        val expected = setOf("a", "b")
        CsvTable("t.csv", "b,a\n").requireColumns(expected)
        assertEquals(
            "t.csv has columns no stored column holds: [c]",
            assertFailsWith<GoldenRefusal> { CsvTable("t.csv", "a,b,c\n").requireColumns(expected) }.message,
        )
        assertEquals(
            "t.csv lacks columns: [b]",
            assertFailsWith<GoldenRefusal> { CsvTable("t.csv", "a\n").requireColumns(expected) }.message,
        )
    }

    @Test
    fun numericCellsParseExactlyAndARefusalNamesTheCellButNotItsText() {
        val row = CsvTable("t.csv", "i,d,x\n9007199254740993,0.006529547739773989,1.5\n").rows().single()
        assertEquals(9_007_199_254_740_993L, row.longOrNull("i"))
        assertEquals(0.006529547739773989, row.double("d"))
        assertEquals("t.csv line 2, x is not an integer", assertFailsWith<GoldenRefusal> { row.int("x") }.message)
    }

    @Test
    fun fieldsTellNullTextNumbersAndBooleansApartAndKeepIntegersExact() {
        val fields =
            Fields(
                "j",
                parseJson(
                    """{"big": 9007199254740993, "d": -1.5e-3, "s": "1", "t": "true", "b": true, "n": null, "f": 2}""",
                    "j",
                ),
            )
        assertEquals(9_007_199_254_740_993L, fields.long("big"))
        assertEquals(-0.0015, fields.double("d"))
        assertEquals("1", fields.text("s"))
        assertEquals(true, fields.bool("b"))
        assertNull(fields.textOrNull("n"))
        assertNull(fields.longOrNull("n"))
        assertNull(fields.objOrNull("n"))
        assertEquals("j.s is not a number", assertFailsWith<GoldenRefusal> { fields.long("s") }.message)
        assertEquals("j.b is not a number", assertFailsWith<GoldenRefusal> { fields.long("b") }.message)
        assertEquals("j.big is not a string", assertFailsWith<GoldenRefusal> { fields.text("big") }.message)
        assertEquals("j.t is not a boolean", assertFailsWith<GoldenRefusal> { fields.bool("t") }.message)
        assertEquals("j.n is not a boolean", assertFailsWith<GoldenRefusal> { fields.bool("n") }.message)
        assertEquals("j.d is not an integer", assertFailsWith<GoldenRefusal> { fields.long("d") }.message)
        assertEquals("j.f is neither 0 nor 1", assertFailsWith<GoldenRefusal> { fields.flag("f") }.message)
        assertEquals("j has no c", assertFailsWith<GoldenRefusal> { fields.text("c") }.message)
        assertEquals(
            "j lacks [c]",
            assertFailsWith<GoldenRefusal> { fields.requireKeys(setOf("big", "d", "s", "t", "b", "n", "f", "c")) }
                .message,
        )
        assertTrue(
            assertFailsWith<GoldenRefusal> { parseJson("{\"a\": 1,}", "x.json") }
                .message!!
                .startsWith("x.json is not JSON")
        )
    }

    @Test
    fun serialNamesSelectTheirConstants() {
        assertEquals(DistanceSource.phoneGps, serialEnum<DistanceSource>("gps:phone", "x"))
        assertEquals(DistanceSource.controller, serialEnum<DistanceSource>("controller", "x"))
        assertEquals(
            "x is not a DistanceSource",
            assertFailsWith<GoldenRefusal> { serialEnum<DistanceSource>("phoneGps", "x") }.message,
        )
    }
}
