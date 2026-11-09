@file:OptIn(ExperimentalJsExport::class)

package app.powerlog.data

import kotlin.js.Promise
import kotlin.js.json
import kotlinx.coroutines.test.TestResult

private val cases: List<Pair<String, BrowserCase>> =
    listOf(
        browserCase("IndexedDbTest.opensADatabaseAndDeletesIt") {
            Promise<Unit> { resolve, reject ->
                    val name = "power-log-browser-suite"
                    val opening = js("indexedDB").open(name, 1)
                    opening.onupgradeneeded = { opening.result.createObjectStore("rows") }
                    opening.onerror = { reject(IllegalStateException("open failed: ${opening.error}")) }
                    opening.onsuccess = {
                        opening.result.close()
                        val deleting = js("indexedDB").deleteDatabase(name)
                        deleting.onerror = { reject(IllegalStateException("delete failed: ${deleting.error}")) }
                        deleting.onsuccess = { resolve(Unit) }
                    }
                }
                .unsafeCast<TestResult>()
        }
    ) + idbCases

@JsExport
public fun runBrowserSuite(fixtures: String, done: (report: String) -> Unit) {
    runCases(cases, JSON.parse(fixtures), done)
}

internal typealias BrowserCase = (fixtures: dynamic, finish: (failure: String?) -> Unit) -> Unit

internal fun browserCase(name: String, test: (fixtures: dynamic) -> TestResult): Pair<String, BrowserCase> =
    "app.powerlog.data.$name" to
        { fixtures, finish ->
            test(fixtures).unsafeCast<Promise<Unit>>().then({ finish(null) }, { error -> finish(error.toString()) })
        }

internal fun runCases(cases: List<Pair<String, BrowserCase>>, fixtures: dynamic, done: (report: String) -> Unit) {
    val results = mutableListOf<String>()
    fun next(index: Int) {
        if (index == cases.size) return done("[${results.joinToString(",")}]")
        val (name, run) = cases[index]
        var settled = false
        val finish = { failure: String? ->
            if (!settled) {
                settled = true
                results += JSON.stringify(json("name" to name, "failure" to failure))
                next(index + 1)
            }
        }
        try {
            run(fixtures, finish)
        } catch (error: Throwable) {
            finish(error.toString())
        }
    }
    next(0)
}
