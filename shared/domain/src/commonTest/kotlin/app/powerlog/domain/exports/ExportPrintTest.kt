package app.powerlog.domain.exports

import app.powerlog.domain.CONTRACT_FIXTURES
import app.powerlog.domain.analytics.JsonParser
import app.powerlog.domain.analytics.readFixtureFile
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.measurement.Producer
import app.powerlog.domain.core.ride.Platform
import kotlin.test.Test
import kotlin.test.assertEquals

class ExportPrintTest {
    private val fixture = JsonParser(readFixtureFile("$CONTRACT_FIXTURES/domain/print-rules.json")).parse()

    @Test
    fun rowTimestampsPrintMillisecondsTruncated() {
        for (case in fixture["timestamp"].list()) assertEquals(
            case["text"].text(),
            ExportPrint.timestamp(case["utcUs"].long()),
        )
    }

    @Test
    fun healthTimestampsPrintNineDigitsOnTheIphoneAndInstantFormElsewhere() {
        for (case in fixture["healthTimestamp"].list()) assertEquals(
            case["text"].text(),
            ExportPrint.healthTimestamp(case["utcUs"].long(), Platform.valueOf(case["platform"].text())),
        )
    }

    @Test
    fun secondsAreMicrosecondsOverAMillionNegativeElapsedIncluded() {
        for (case in fixture["seconds"].list()) assertEquals(
            case["seconds"].double(),
            ExportPrint.seconds(case["us"].long()),
        )
    }

    @Test
    fun theProviderAndEventProducerFollowThePlatform() {
        for (case in fixture["provider"].list()) assertEquals(
            case["provider"].optionalText()?.let(HealthProviderKind::valueOf),
            ExportPrint.provider(Platform.valueOf(case["platform"].text())),
        )
        for (case in fixture["eventProducer"].list()) assertEquals(
            Producer.valueOf(case["producer"].text()),
            ExportPrint.eventProducer(Platform.valueOf(case["platform"].text())),
        )
    }
}
