package app.powerlog.core.health

import app.powerlog.core.analytics.J
import app.powerlog.core.protocol.ProtocolException
import kotlin.test.*

class HealthCodecTest {
    @Test
    fun rawOriginAndProviderMembersPreserveOriginalPrecision() {
        val origin = Origin(Provider.appleHealth, "app.powerlog.mobile", PID, PID, Source.phone, Long.MAX_VALUE)
        assertEquals(origin, HealthCodec.decodeOrigin(HealthCodec.encodeOrigin(origin)))
        val tuple = MemberTuple(Long.MIN_VALUE, 999, Long.MAX_VALUE, 999, "heart-rate", "bpm", "405efc0000000000")
        val encoded = HealthCodec.encodeMemberTuple(tuple)
        assertEquals(tuple, HealthCodec.decodeMemberTuple(encoded))
        assertEquals(encoded, HealthCodec.encodeMemberTuple(HealthCodec.decodeMemberTuple(encoded)))
        assertFailsWith<ProtocolException> { HealthCodec.encodeMemberTuple(tuple.copy(startSubUsNs = 1000)) }
        assertFailsWith<ProtocolException> { HealthCodec.encodeMemberTuple(tuple.copy(valueBits = "7ff0000000000000")) }
    }

    @Test
    fun providerStatusAndCallBudgetRoundTripWithoutPublicationLedger() {
        val component =
            ComponentResult(Component.riderPower, 1, true, ComponentState.failed, HealthReason.permission, emptyList())
        assertEquals(component, HealthCodec.decodeComponentResult(HealthCodec.encodeComponentResult(component)))
        val budget =
            PublicationBudget(
                Provider.healthConnect,
                listOf(BudgetCall(0, CallBucket.write, ExecutionMode.background, 500)),
                Long.MAX_VALUE,
            )
        assertEquals(budget, HealthCodec.decodePublicationBudget(HealthCodec.encodePublicationBudget(budget)))
        assertFailsWith<ProtocolException> { HealthCodec.encodeComponentResult(component.copy(reason = null)) }
        assertFailsWith<ProtocolException> {
            HealthCodec.encodePublicationBudget(
                budget.copy(calls = listOf(BudgetCall(0, CallBucket.write, ExecutionMode.background, -1)))
            )
        }
    }

    @Test
    fun projectionUsesDeterministicIdentityAndVersionOne() {
        val plan =
            RecordPlan(
                HealthIds.second(R, HealthKind.riderPower, 0),
                Component.riderPower,
                RecordOperation.upsert,
                quantity(J.Str("A")),
                1,
                null,
            )
        val first = Publication.plan(plan)
        assertEquals(plan.syncId, first.syncId)
        assertEquals(1L, first.version)
        assertEquals(first, Publication.plan(plan))
        assertEquals(RecordState.notSubmitted, first.state)
        assertEquals(1L, Publication.plan(plan.copy(content = quantity(J.Str("B")))).version)
    }

    @Test
    fun routeCodecPreservesProviderCoordinatesAndRejectsOutOfBounds() {
        val point = RoutePoint("seq:phone:1", Long.MAX_VALUE, -90.0, 180.0, 0.1, null, 123.456789)
        assertEquals(point, HealthCodec.decodeRoutePoint(HealthCodec.encodeRoutePoint(point)))
        assertFailsWith<ProtocolException> { HealthCodec.encodeRoutePoint(point.copy(latitude = 90.0001)) }
        assertFailsWith<ProtocolException> { HealthCodec.encodeRoutePoint(point.copy(longitude = 180.0001)) }
    }
}
