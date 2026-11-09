package app.powerlog.data

import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserSuiteTest {
    @Test
    fun reportsEveryCaseByNameAndKeepsGoingAfterAFailure() {
        var report: String? = null
        val cases: List<Pair<String, BrowserCase>> =
            listOf(
                "passes" to { _, finish -> finish(null) },
                "fails" to { _, finish -> finish("not granted") },
                "throws" to { _, _ -> error("thrown") },
            )
        runCases(cases, null) { report = it }
        assertEquals(
            """[{"name":"passes","failure":null},{"name":"fails","failure":"not granted"},""" +
                """{"name":"throws","failure":"IllegalStateException: thrown"}]""",
            report,
        )
    }
}
