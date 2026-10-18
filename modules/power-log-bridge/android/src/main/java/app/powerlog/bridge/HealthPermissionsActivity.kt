package app.powerlog.bridge

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.health.connect.client.PermissionController

class HealthPermissionsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launcher =
            registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
                setResult(Activity.RESULT_OK)
                finish()
            }
        if (savedInstanceState == null) {
            val engine = RecordingEngine.get(applicationContext)
            launcher.launch(engine.health.permissions(intent.getBooleanExtra("gps", false)))
        }
    }
}
