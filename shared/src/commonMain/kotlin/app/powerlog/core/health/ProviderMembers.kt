package app.powerlog.core.health

import app.powerlog.core.Sha256
import app.powerlog.core.Sha256Accumulator
import app.powerlog.core.analytics.FixedPoint
import app.powerlog.core.protocol.*

data class MemberTuple(
    val startUs: Long,
    val startSubUsNs: Int,
    val endUs: Long,
    val endSubUsNs: Int,
    val metric: String,
    val unit: String,
    val valueBits: String,
)

data class MemberInput(val tuple: MemberTuple, val originalStart: String, val originalEnd: String)

data class MemberCursor(
    val snapshotDigest: String,
    val nextIndex: Long,
    val previous: MemberTuple?,
    val nextOccurrence: Long,
)

data class MemberPage(val members: List<ProviderMember>, val cursor: MemberCursor)

object ProviderMembers {
    @Throws(
        IllegalArgumentException::class,
        ArithmeticException::class,
        app.powerlog.core.protocol.ProtocolException::class,
    )
    fun normalized(tuple: MemberTuple): MemberTuple {
        require(tuple.startSubUsNs in 0..999 && tuple.endSubUsNs in 0..999)
        require(tuple.valueBits.length == 16 && tuple.valueBits.all { it in '0'..'9' || it in 'a'..'f' })
        val value = Double.fromBits(tuple.valueBits.toULong(16).toLong())
        require(value.isFinite())
        return if (value == 0.0) tuple.copy(valueBits = "0000000000000000") else tuple
    }

    @Throws(
        IllegalArgumentException::class,
        ArithmeticException::class,
        app.powerlog.core.protocol.ProtocolException::class,
    )
    fun compare(left: MemberTuple, right: MemberTuple): Int {
        val a = normalized(left)
        val b = normalized(right)
        val orders =
            listOf(
                a.startUs.compareTo(b.startUs),
                a.startSubUsNs.compareTo(b.startSubUsNs),
                a.endUs.compareTo(b.endUs),
                a.endSubUsNs.compareTo(b.endSubUsNs),
                app.powerlog.core.analytics.compareUtf8(a.metric, b.metric),
                app.powerlog.core.analytics.compareUtf8(a.unit, b.unit),
                a.valueBits.toULong(16).compareTo(b.valueBits.toULong(16)),
            )
        return orders.firstOrNull { it != 0 } ?: 0
    }

    internal fun canonical(tuple: MemberTuple): String {
        val t = normalized(tuple)
        return JsonCodec.encode(
            Obj(
                mapOf(
                    "startUs" to Str(t.startUs.toString()),
                    "startSubUsNs" to Num(t.startSubUsNs.toDouble()),
                    "endUs" to Str(t.endUs.toString()),
                    "endSubUsNs" to Num(t.endSubUsNs.toDouble()),
                    "metric" to Str(t.metric),
                    "unit" to Str(t.unit),
                    "valueBits" to Str(t.valueBits),
                )
            )
        )
    }

    @Throws(
        IllegalArgumentException::class,
        ArithmeticException::class,
        app.powerlog.core.protocol.ProtocolException::class,
    )
    fun logicalKey(parent: ProviderParent, index: Long): String =
        "health:${parent.provider.name}:${parent.dataOrigin}:${parent.providerId}:${parent.snapshotDigest}:member:$index"

    @Throws(
        IllegalArgumentException::class,
        ArithmeticException::class,
        app.powerlog.core.protocol.ProtocolException::class,
    )
    fun eventId(key: String): String {
        val hex = Sha256.hash(key.encodeToByteArray()).take(32).toCharArray()
        hex[12] = '5'
        hex[16] = "89ab"[hex[16].digitToInt(16) and 3]
        val s = hex.concatToString()
        return "${s.take(8)}-${s.substring(8,12)}-${s.substring(12,16)}-${s.substring(16,20)}-${s.substring(20)}"
    }

    @Throws(
        IllegalArgumentException::class,
        ArithmeticException::class,
        app.powerlog.core.protocol.ProtocolException::class,
    )
    fun page(parent: ProviderParent, cursor: MemberCursor, sortedPage: Array<MemberInput>): MemberPage {
        require(
            sortedPage.size <= 128 &&
                cursor.snapshotDigest == parent.snapshotDigest &&
                cursor.nextIndex >= 0 &&
                cursor.nextIndex + sortedPage.size <= parent.memberCount
        )
        var index = cursor.nextIndex
        var previous = cursor.previous
        var occurrence = cursor.nextOccurrence
        val members = sortedPage.map { input ->
            val t = normalized(input.tuple)
            require(previous == null || compare(requireNotNull(previous), t) <= 0)
            if (previous != t) occurrence = 0
            val key = logicalKey(parent, index)
            ProviderMember(
                    parent,
                    index++,
                    occurrence++,
                    input.originalStart,
                    input.originalEnd,
                    input.tuple.valueBits,
                    t.unit,
                    key,
                    eventId(key),
                )
                .also { previous = t }
        }
        return MemberPage(members, MemberCursor(parent.snapshotDigest, index, previous, occurrence))
    }

    @Throws(
        IllegalArgumentException::class,
        ArithmeticException::class,
        app.powerlog.core.protocol.ProtocolException::class,
    )
    fun checkCollision(eventId: String, fullKey: String, retainedFullKey: String?) {
        require(eventId == eventId(fullKey) && (retainedFullKey == null || retainedFullKey == fullKey))
    }

    @Throws(
        IllegalArgumentException::class,
        ArithmeticException::class,
        app.powerlog.core.protocol.ProtocolException::class,
    )
    fun exhausted(sampleCount: Int, deletedCount: Int): Boolean {
        require(sampleCount >= 0 && deletedCount >= 0)
        return sampleCount == 0 && deletedCount == 0
    }

    @Throws(
        IllegalArgumentException::class,
        ArithmeticException::class,
        app.powerlog.core.protocol.ProtocolException::class,
    )
    fun publishImportedMember(member: ProviderMember): List<RecordPlan> =
        emptyList<RecordPlan>().also { HealthValidation.validate(member) }

    @Throws(
        IllegalArgumentException::class,
        ArithmeticException::class,
        app.powerlog.core.protocol.ProtocolException::class,
    )
    fun admitRevision(
        previous: ProviderParent?,
        next: ProviderParent,
        previousMetadata: String?,
        nextMetadata: String?,
    ): Boolean {
        if (previous == null) return true
        require(previous.nativeRevision == next.nativeRevision)
        require(sameSnapshot(previous, next) && previous == next && previousMetadata == nextMetadata) {
            "The same native parent revision has conflicting metadata."
        }
        return false
    }

    @Throws(
        IllegalArgumentException::class,
        ArithmeticException::class,
        app.powerlog.core.protocol.ProtocolException::class,
    )
    fun sameSnapshot(parent: ProviderParent, next: ProviderParent): Boolean {
        require(
            parent.provider == next.provider &&
                parent.dataOrigin == next.dataOrigin &&
                parent.providerId == next.providerId
        )
        require(
            parent.nativeRevision == null ||
                next.nativeRevision == null ||
                parent.nativeRevision != next.nativeRevision ||
                parent.snapshotDigest == next.snapshotDigest
        )
        return parent.snapshotDigest == next.snapshotDigest
    }
}

class ParentManifestBuilder {
    private val hash = Sha256Accumulator()
    private var previous: MemberTuple? = null
    private var count = 0L
    private var finished = false

    @Throws(
        IllegalArgumentException::class,
        ArithmeticException::class,
        app.powerlog.core.protocol.ProtocolException::class,
    )
    fun append(sortedPage: Array<MemberTuple>) {
        require(!finished && sortedPage.size <= 128)
        for (tuple in sortedPage) {
            val t = ProviderMembers.normalized(tuple)
            require(previous == null || ProviderMembers.compare(requireNotNull(previous), t) <= 0)
            hash.update(ProviderMembers.canonical(t).encodeToByteArray())
            hash.update(byteArrayOf(10))
            previous = t
            count = FixedPoint.add(count, 1)
        }
    }

    @Throws(
        IllegalArgumentException::class,
        ArithmeticException::class,
        app.powerlog.core.protocol.ProtocolException::class,
    )
    fun finish(expectedCount: Long, eof: Boolean): String {
        require(!finished && eof && count == expectedCount)
        finished = true
        return hash.digest()
    }
}
