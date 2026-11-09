package app.powerlog.platform.health

import android.content.Context
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.health.connect.client.PermissionController
import app.powerlog.platform.ui.AndroidUiContext

object HealthConnectPermissionRequest {
    fun start(context: Context, ui: AndroidUiContext, permissions: Set<String>, closed: (shown: Boolean) -> Unit) {
        val intent = PermissionController.createRequestPermissionResultContract().createIntent(context, permissions)
        // From Android 14 the contract answers AndroidX's permission action, which no Activity resolves: only a
        // runtime permission request serves it, and the system then shows Health Connect's own screen.
        if (intent.action == RequestMultiplePermissions.ACTION_REQUEST_PERMISSIONS) {
            val requested = intent.getStringArrayExtra(RequestMultiplePermissions.EXTRA_PERMISSIONS).orEmpty().toList()
            if (!ui.requestPermissions(requested) { closed(true) }) closed(false)
        } else {
            ui.startForResult(intent) { resultCode, _ -> closed(resultCode != null) }
        }
    }
}
