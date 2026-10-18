package app.powerlog.core.health

import app.powerlog.core.analytics.J
import app.powerlog.core.protocol.ProtocolException
import kotlin.test.Test

class HealthReviewTest : HealthFixtureTest() {
    @Test
    fun healthThrowingBoundaryValidation() {
        fails<IllegalArgumentException> { RouteRetentionCursor(intArrayOf(1, 0)) }
        val present = quantity(J.Str("A")) as NormalizedContent.Present
        val fields = present.fields as ProviderContent.Quantity
        val invalid = ProviderContent.Quantity(fields.fields.copy(value = Double.NaN))
        fails<ProtocolException> {
            HealthCodec.contentDigest(NormalizedContent.Present(invalid, AdapterProjection.Apple(invalid)))
        }
        fails<IllegalArgumentException> { SecondKernel.policy(HealthKind.cyclingDistance) }
        fails<IllegalArgumentException> { MeasurementSource("invalid", "controller") }
    }
}
