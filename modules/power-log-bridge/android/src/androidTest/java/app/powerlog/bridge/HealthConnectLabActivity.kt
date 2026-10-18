package app.powerlog.bridge

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView

class HealthConnectLabActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply { text = "Power Log Lab" })
    }
}
