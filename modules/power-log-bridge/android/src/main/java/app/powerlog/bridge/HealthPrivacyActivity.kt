package app.powerlog.bridge

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

class HealthPrivacyActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        title = "Power Log privacy"
        val text =
            TextView(this).apply {
                textSize = 18f
                setPadding(24, 24, 24, 24)
                text =
                    "Power Log stores rides on this device. It has no account, advertising or analytics service.\n\nWhen you enable Health Connect, Power Log writes your completed cycling sessions, rider power, cadence, speed, distance and recorded route to Health Connect with your permission. It does not read other apps’ health records.\n\nYou can revoke access in Health Connect. Deleting a ride in Power Log leaves an already exported Health Connect copy unchanged; manage that copy in Health Connect.\n\nFIT, ZIP and CSV files leave the app only when you choose to export or share them. Files with routes contain location data."
            }
        setContentView(ScrollView(this).apply { addView(text) })
    }
}
