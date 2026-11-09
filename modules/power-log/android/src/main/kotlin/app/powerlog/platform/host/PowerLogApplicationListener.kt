package app.powerlog.platform.host

import android.app.Application
import expo.modules.core.interfaces.ApplicationLifecycleListener

class PowerLogApplicationListener : ApplicationLifecycleListener {
    override fun onCreate(application: Application) {
        PowerLogAndroid.start(application)
    }
}
