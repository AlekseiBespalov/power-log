package app.powerlog.platform.files

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object ExportSharing {
    fun share(activity: Activity?, file: Map<String, Any?>): String {
        if (activity == null) throw SinkException("uiUnavailable", "Power Log has no screen to share from.")
        val uri = shareable(activity, file["uri"] as? String ?: throw invalid())
        val intent =
            Intent(Intent.ACTION_SEND)
                .setType(file["mimeType"] as? String ?: "application/octet-stream")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(Intent.createChooser(intent, "Export ride"))
        return "shared"
    }

    fun shareable(context: Context, uri: String): Uri {
        val parsed = Uri.parse(uri)
        val authority = "${context.packageName}.powerlog.files"
        val committed = File(context.filesDir, "exports").canonicalFile
        if (parsed.scheme != "content" || parsed.authority != authority) throw invalid()
        if (parsed.pathSegments.firstOrNull() != "ride-exports") throw invalid()
        val file = File(committed, parsed.pathSegments.drop(1).joinToString(File.separator)).canonicalFile
        if (!file.isFile || !file.path.startsWith(committed.path + File.separator)) throw invalid()
        return FileProvider.getUriForFile(context, authority, file)
    }

    private fun invalid() = SinkException("invalid", "This export file is unavailable.")
}
