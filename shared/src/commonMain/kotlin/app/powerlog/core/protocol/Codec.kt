package app.powerlog.core.protocol

object WatchProtocol {
    @Throws(ProtocolException::class)
    fun decodeEvent(bytes: ByteArray, rideId: String): Event = Schema.event(JsonCodec.parse(bytes), uuid(Str(rideId)))

    @Throws(ProtocolException::class)
    fun canonicalizeJson(bytes: ByteArray): ByteArray = JsonCodec.encode(JsonCodec.parse(bytes)).encodeToByteArray()
}

internal const val DISTANCE_SOURCES = "auto gps:phone controller"
