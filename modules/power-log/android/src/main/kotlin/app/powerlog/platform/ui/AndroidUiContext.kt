package app.powerlog.platform.ui

import android.app.Activity
import android.content.Intent

interface AndroidUiContext {
    fun activity(): Activity?

    // false: no Activity or permission manager, so no prompt
    fun requestPermissions(permissions: List<String>, done: (Map<String, Boolean>) -> Unit): Boolean

    // posts to the main thread; a null result code means no Activity could start it
    fun startForResult(intent: Intent, done: (resultCode: Int?, data: Intent?) -> Unit)
}
