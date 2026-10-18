package app.powerlog.bridge

import android.content.Context
import app.powerlog.core.health.*
import org.junit.Assert.*

internal fun healthQueryPlan(store: RideStore, sql: String, args: Array<Any>): List<String> {
    val observer = store.queryObserver
    store.queryObserver = null
    return try {
        store.select("EXPLAIN QUERY PLAN $sql", args) { c ->
            buildList { while (c.moveToNext()) add(c.getString(3)) }
        }
    } finally {
        store.queryObserver = observer
    }
}

internal fun assertHealthSecondReadsBounded(context: Context, store: RideStore, id: String, seconds: Int) {
    val plans = HealthPlans(context, store)
    val revision = store.revision(id)
    val start = timeUs(store.metadata(id).str("startedAt"))
    val kinds = listOf(HealthKind.riderPower, HealthKind.cyclingCadence, HealthKind.cyclingSpeed)
    var reads = 0
    store.queryObserver = { sql, args ->
        if ("query_us BETWEEN" in sql) {
            val plan = healthQueryPlan(store, sql, args)
            assertTrue(
                "Health second scans ride history: $plan",
                plan.any { "ride=? AND kind=? AND query_us>" in it && "query_us<" in it },
            )
            reads++
        }
    }
    try {
        store.transaction {
            for (n in seconds - 33L until seconds - 1L) for (kind in kinds) {
                val result =
                    plans.second(id, kind, SecondBuckets.bounds(start, start + seconds * 1000000L, n), revision)
                assertTrue(result.content is NormalizedContent.Present)
            }
        }
        assertEquals(96, reads)
    } finally {
        store.queryObserver = null
    }
}

internal fun assertHealthCandidateReadBounded(store: RideStore, sql: String, args: Array<Any>): Pair<Int, Int> {
    val plan = healthQueryPlan(store, sql, args)
    val candidates = plan.filter { "SEARCH o " in it }
    assertTrue("Health candidates scan another metric: $plan", candidates.isNotEmpty())
    assertTrue(
        "Health candidates lack their metric/time scope: $plan",
        candidates.all { "ride=? AND <expr>=?" in it && "elapsed_us" in it },
    )
    if ("(o.elapsed_us,o.producer,o.producer_sequence,o.id)>" in sql)
        assertTrue(
            "Health candidates replay equal-time history: $plan",
            candidates.all { "(elapsed_us,producer,producer_sequence)>" in it },
        )
    assertTrue(
        "Selection filters the candidate range before LIMIT: $sql",
        sql.indexOf("AS health_selected") in 1 until sql.indexOf("WHERE o.ride=?"),
    )
    val observer = store.queryObserver
    store.queryObserver = null
    return try {
        store.select(sql, args) { c ->
            var examined = 0
            var rejected = 0
            while (c.moveToNext()) {
                examined++
                if (c.long("health_selected") == 0L) rejected++
            }
            assertTrue("Health candidate page examines $examined originals", examined <= 256)
            examined to rejected
        }
    } finally {
        store.queryObserver = observer
    }
}
