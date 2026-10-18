package app.powerlog.core.protocol

object ProtocolRecords {
    @Throws(ProtocolException::class)
    fun encodeDistance(value: DisplayDistance): String =
        JsonCodec.encode(ControllerSchema.distance(value.json()).json())

    @Throws(ProtocolException::class)
    fun decodeDistance(text: String): DisplayDistance =
        ControllerSchema.distance(JsonCodec.parse(text.encodeToByteArray()))
}
