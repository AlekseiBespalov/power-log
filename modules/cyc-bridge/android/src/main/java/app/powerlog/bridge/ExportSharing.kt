package app.powerlog.bridge

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

internal object ExportSharing {
    private const val UNAVAILABLE = "Export file is unavailable."

    fun shareable(context: Context, uri: String): Uri {
        val parsed = Uri.parse(uri)
        val authority = "${context.packageName}.powerlog.files"
        val committed = File(context.filesDir, "exports").canonicalFile
        val file =
            when (parsed.scheme) {
                "content" -> {
                    require(parsed.authority == authority && parsed.pathSegments.firstOrNull() == "ride-exports") {
                        UNAVAILABLE
                    }
                    File(committed, parsed.pathSegments.drop(1).joinToString(File.separator))
                }
                "file" -> File(requireNotNull(parsed.path) { UNAVAILABLE })
                else -> throw IllegalArgumentException(UNAVAILABLE)
            }.canonicalFile
        require(file.isFile && file.path.startsWith(committed.path + File.separator)) { UNAVAILABLE }
        return FileProvider.getUriForFile(context, authority, file)
    }

    fun mimeType(uri: Uri): String =
        when (uri.lastPathSegment?.substringAfterLast('.', "")) {
            "csv" -> "text/csv"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
}
