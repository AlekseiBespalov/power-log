package app.powerlog.runtime.web.host

import app.powerlog.application.api.ApiFailure
import app.powerlog.application.api.LaunchContext
import app.powerlog.application.health.HealthWorkResult
import app.powerlog.application.recording.RideCardIntent
import app.powerlog.domain.core.failure.RefusalDetail
import app.powerlog.runtime.host.HostFacade
import kotlin.test.Test
import kotlin.test.assertEquals

class WebHostTest {
    private val calls = mutableListOf<String>()
    private val host =
        object : HostFacade {
            override fun launch(context: LaunchContext, done: (RefusalDetail?) -> Unit) {
                calls += "launch"
            }

            override fun foregroundChanged(foreground: Boolean) {
                calls += "foreground $foreground"
            }

            override fun didEnterBackground(done: () -> Unit) {
                calls += "background"
                done()
            }

            override fun willTerminate(timeoutUs: Long): Boolean = true

            override fun rideCardIntent(intent: RideCardIntent, done: (ApiFailure?) -> Unit) {}

            override fun runHealthWork(rideId: String, attempt: Int, done: (HealthWorkResult) -> Unit) {}
        }

    @Test
    fun aHiddenPageIsBackgroundAndAVisibleOneForeground() {
        val page = FakePage("visible")

        WebHost.forward(host, page.handle)
        page.show("hidden")
        page.show("visible")
        page.hide()

        assertEquals(
            listOf("foreground true", "foreground false", "background", "foreground true", "background"),
            calls,
        )
    }

    @Test
    fun aPageStartingHiddenStartsInTheBackground() {
        WebHost.forward(host, FakePage("hidden").handle)

        assertEquals(listOf("foreground false"), calls)
    }

    private class FakePage(private var state: String) {
        private val listeners = mutableMapOf<String, (dynamic) -> Unit>()
        val handle: BrowserPage

        init {
            val document: dynamic = js("({})")
            val page: dynamic = js("({})")
            js("Object").defineProperty(document, "visibilityState", jsGetter { state })
            document.addEventListener = { type: String, listener: (dynamic) -> Unit ->
                listeners["document $type"] = listener
            }
            page.addEventListener = { type: String, listener: (dynamic) -> Unit ->
                listeners["window $type"] = listener
            }
            page.document = document
            page.navigator = js("({})")
            handle = page.unsafeCast<BrowserPage>()
        }

        fun show(next: String) {
            state = next
            listeners.getValue("document visibilitychange")(js("({})"))
        }

        fun hide() {
            listeners.getValue("window pagehide")(js("({})"))
        }

        private fun jsGetter(read: () -> String): dynamic {
            val descriptor: dynamic = js("({})")
            descriptor.get = read
            return descriptor
        }
    }
}
