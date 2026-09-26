package app.powerlog.bridge

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.health.connect.client.PermissionController

/** Health Connect's contract also handles Android 14+ runtime permission requests. */
class HealthPermissionsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launcher =
            registerForActivityResult(
                PermissionController.createRequestPermissionResultContract()
            ) {
                setResult(Activity.RESULT_OK)
                finish()
            }
        if (savedInstanceState == null) {
            val engine = RecordingEngine.get(applicationContext)
            launcher.launch(engine.health.permissions(intent.getBooleanExtra("gps", false)))
        }
    }
}
