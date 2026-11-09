@file:OptIn(ExperimentalForeignApi::class)

package app.powerlog.runtime.compose

import kotlinx.cinterop.ExperimentalForeignApi
import platform.darwin.DISPATCH_AUTORELEASE_FREQUENCY_WORK_ITEM
import platform.darwin.DISPATCH_QUEUE_CONCURRENT
import platform.darwin.NSObject
import platform.darwin.dispatch_queue_attr_make_with_autorelease_frequency
import platform.darwin.dispatch_queue_attr_make_with_qos_class
import platform.darwin.dispatch_queue_attr_t
import platform.darwin.dispatch_queue_create
import platform.posix.QOS_CLASS_USER_INITIATED
import platform.posix.QOS_CLASS_UTILITY
import platform.posix.qos_class_t

internal object AppleQueueFactory {
    enum class Priority(val qos: qos_class_t) {
        userInitiated(QOS_CLASS_USER_INITIATED),
        utility(QOS_CLASS_UTILITY),
    }

    fun serial(label: String, priority: Priority): NSObject = queue(label, null, priority)

    fun concurrent(label: String, priority: Priority): NSObject = queue(label, DISPATCH_QUEUE_CONCURRENT, priority)

    private fun queue(label: String, kind: dispatch_queue_attr_t, priority: Priority): NSObject {
        val autorelease =
            dispatch_queue_attr_make_with_autorelease_frequency(kind, DISPATCH_AUTORELEASE_FREQUENCY_WORK_ITEM)
        return dispatch_queue_create(label, dispatch_queue_attr_make_with_qos_class(autorelease, priority.qos, 0))!!
    }
}
