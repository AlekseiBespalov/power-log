package app.powerlog.domain.core.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TimeTest {
    @Test
    fun utcParsesTodaysGrammarToMicroseconds() {
        assertEquals(946_684_800_000_000L, Utc.parseUs("2000-01-01T00:00:00Z"))
        assertEquals(1_788_706_200_123_456L, Utc.parseUs("2026-09-06T14:50:00.123456789Z"))
        assertEquals(1_788_706_200_100_000L, Utc.parseUs("2026-09-06T14:50:00.1+00:00"))
        assertEquals(1_788_706_200_000_000L, Utc.parseUs("2026-9-6T14:50:00Z"))
        assertEquals(4_102_444_799_999_999L, Utc.parseUs("2099-12-31T23:59:59.999999Z"))
        assertEquals(1_709_164_800_000_000L, Utc.parseUs("2024-02-29T00:00:00Z"))
    }

    @Test
    fun utcRefusesEverythingOutsideTheGrammar() {
        listOf(
                "1999-12-31T23:59:59Z",
                "2100-01-01T00:00:00Z",
                "2026-13-01T00:00:00Z",
                "2026-01-32T00:00:00Z",
                "2026-01-01T00:60:00Z",
                "2026-01-01T00:00:60Z",
                "2026-01-01T00:00:00",
                "2026-01-01T00:00:00+01:00",
                "2026-01-01T00:00:00.1234567890Z",
                "2026-01-01 00:00:00Z",
                "",
            )
            .forEach { assertNull(Utc.parseUs(it), it) }
    }

    @Test
    fun microsFloorsSecondsAsTodaysConversion() {
        assertEquals(510L, Micros.of(0.000511))
        assertEquals(1_500_000L, Micros.of(1.5))
        assertEquals(-1L, Micros.of(-0.0000001))
        assertEquals(0L, Micros.of(0.0))
        assertFailsWith<IllegalArgumentException> { Micros.of(Double.NaN) }
    }

    @Test
    fun microsParsesPrintedSecondsExactly() {
        assertEquals(511L, Micros.parse("0.000511"))
        assertEquals(1_500_000L, Micros.parse("1.5"))
        assertEquals(12_345_678L, Micros.parse("12.3456789"))
        assertEquals(-2_250_000L, Micros.parse("-2.25"))
        assertEquals(0L, Micros.parse("-0"))
        assertEquals(86_400_000_000L, Micros.parse("86400"))
        listOf("", "1.", ".5", "+1", "1e-7", "1,5", " 1", "NaN", "99999999999999999999").forEach {
            assertNull(Micros.parse(it), it)
        }
    }
}
