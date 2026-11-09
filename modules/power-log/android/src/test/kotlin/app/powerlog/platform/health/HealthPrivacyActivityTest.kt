package app.powerlog.platform.health

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class HealthPrivacyActivityTest {
    @Test
    fun healthConnectPrivacyLinksOpenTheAppPrivacyPolicyAndClose() {
        val context = RuntimeEnvironment.getApplication()
        val launcher = ComponentName(context.packageName, "${context.packageName}.MainActivity")
        shadowOf(context.packageManager).addActivityIfNotPresent(launcher)
        shadowOf(context.packageManager)
            .addIntentFilterForActivity(
                launcher,
                IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
            )
        for (action in
            listOf("androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE", Intent.ACTION_VIEW_PERMISSION_USAGE)) {
            val activity = Robolectric.buildActivity(HealthPrivacyActivity::class.java, Intent(action)).setup().get()
            val opened = requireNotNull(shadowOf(activity).nextStartedActivity)
            assertEquals(launcher, opened.component)
            assertEquals(Intent.ACTION_VIEW, opened.action)
            assertEquals(Uri.parse("power-log:///privacy"), opened.data)
            assertTrue(activity.isFinishing)
        }
    }
}
