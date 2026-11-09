package app.powerlog.platform.health

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

class HealthPrivacyActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val open = requireNotNull(packageManager.getLaunchIntentForPackage(packageName))
        startActivity(open.cloneFilter().setAction(Intent.ACTION_VIEW).setData(Uri.parse("power-log:///privacy")))
        finish()
    }
}
