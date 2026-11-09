package app.powerlog.application.port

public interface FilePicker {
    public fun pickCsv(reply: Reply<PickedFile>)
}

public interface PickedFile {
    public val name: String
    public val sizeBytes: Long

    public fun read(maxBytes: Int, reply: Reply<ByteArray?>)

    public fun close()
}
