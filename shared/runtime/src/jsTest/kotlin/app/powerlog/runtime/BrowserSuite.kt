@file:OptIn(ExperimentalJsExport::class)

package app.powerlog.runtime

import kotlin.js.Promise
import kotlin.js.json
import kotlinx.coroutines.test.TestResult

private val cases: List<Pair<String, BrowserCase>> =
    listOf(
        browserCase("WebLocksTest.grantAnExclusiveLock") {
            Promise<Unit> { resolve, reject ->
                    val locks = js("navigator.locks")
                    if (locks == null) reject(IllegalStateException("navigator.locks is unavailable"))
                    else
                        locks.request(
                            "power-log-browser-suite",
                            json("mode" to "exclusive"),
                            { lock: dynamic ->
                                if (lock == null) reject(IllegalStateException("no lock was granted"))
                                else resolve(Unit)
                            },
                        )
                }
                .unsafeCast<TestResult>()
        }
    ) + webAdapterCases + webHostCases + webSliceCases + encoderPagesCases

@JsExport
public fun runBrowserSuite(fixtures: String, done: (report: String) -> Unit) {
    runCases(cases, JSON.parse(fixtures), done)
}

internal typealias BrowserCase = (fixtures: dynamic, finish: (failure: String?) -> Unit) -> Unit

internal fun browserCase(name: String, test: (fixtures: dynamic) -> TestResult): Pair<String, BrowserCase> =
    "app.powerlog.runtime.$name" to
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
