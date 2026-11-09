package app.powerlog.platform.host

import android.content.Context
import app.powerlog.platform.charts.ChartRasterView
import app.powerlog.platform.files.ExportSharing
import app.powerlog.platform.files.ExportSinks
import app.powerlog.platform.files.SinkException
import app.powerlog.platform.files.SinkReply
import app.powerlog.runtime.bridge.BridgeSession
import expo.modules.kotlin.Promise
import expo.modules.kotlin.functions.Queues
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

private fun Promise.rejectSink(failure: SinkException) = reject(failure.code, failure.message, null)

private fun Promise.sinkReply(): SinkReply = { answer ->
    answer.fold({ resolve(it) }) { error ->
        rejectSink(error as? SinkException ?: ExportSinks.rejection(error as Exception))
    }
}

private fun Promise.rejectSessionMissing() =
    reject("ERR_BRIDGE_SESSION", "The Power Log runtime has no bridge session for this module.", null)

class PowerLogModule internal constructor(private val contextProvider: (() -> Context)?) : Module() {
    constructor() : this(null)

    private var session: BridgeSession? = null
    private val sinks by lazy {
        ExportSinks.create(contextProvider?.invoke() ?: requireNotNull(appContext.reactContext))
    }

    override fun definition() = ModuleDefinition {
        Name("PowerLog")
        OnCreate {
            session = PowerLogAndroid.runtime?.openBridgeSession()
            HostUiContext.attach(this@PowerLogModule, { appContext.currentActivity }, { appContext.permissions })
            PowerLogAndroid.activityIntent(appContext.currentActivity?.intent)
            sinks.cleanup()
        }
        OnNewIntent { intent -> PowerLogAndroid.activityIntent(intent) }
        OnActivityEntersForeground {
            PowerLogAndroid.foregroundChanged(true)
            PowerLogAndroid.activityIntent(appContext.currentActivity?.intent)
        }
        OnActivityEntersBackground {
            PowerLogAndroid.foregroundChanged(false)
            PowerLogAndroid.didEnterBackground()
        }
        OnDestroy {
            session?.close()
            session = null
            HostUiContext.detach(this@PowerLogModule)
            PowerLogAndroid.foregroundChanged(false)
            sinks.abortAll()
        }
        OnActivityResult { _, (requestCode, resultCode, data) ->
            HostUiContext.result(requestCode, resultCode, data)
        }
        View(ChartRasterView::class) {
            Events("onRenderStatus")
            Prop("sourceId") { view: ChartRasterView, value: String -> view.source(value) }
            Prop("sceneKey") { view: ChartRasterView, value: String -> view.key(value) }
            Prop("scene") { view: ChartRasterView, value: String -> view.scene(value) }
            Prop("presentation") { view: ChartRasterView, value: List<Double> ->
                view.presentation(value)
            }
            Prop("selection") { view: ChartRasterView, value: String -> view.selection(value) }
            Prop("selectionTarget") { view: ChartRasterView, value: String -> view.target(value) }
        }
        AsyncFunction("call") { op: String, request: String, promise: Promise ->
            session?.call(op, request) { promise.resolve(it) } ?: promise.rejectSessionMissing()
        }
        AsyncFunction("next") { stream: String, request: String, promise: Promise ->
            session?.next(stream, request) { promise.resolve(it) } ?: promise.rejectSessionMissing()
        }
        AsyncFunction("exportPage") { request: String, promise: Promise ->
            session?.exportPage(request) { page ->
                promise.resolve(mapOf("envelope" to page.envelope, "numbers" to page.numbers))
            } ?: promise.rejectSessionMissing()
        }
        AsyncFunction("sinkOpen") { kind: String, promise: Promise -> sinks.open(kind, promise.sinkReply()) }
        AsyncFunction("sinkWrite") { id: String, bytes: ByteArray, promise: Promise ->
            sinks.write(id, bytes, promise.sinkReply())
        }
        AsyncFunction("sinkWriteAt") { id: String, offset: Double, bytes: ByteArray, promise: Promise ->
            sinks.writeAt(id, offset, bytes, promise.sinkReply())
        }
        AsyncFunction("sinkBeginDeflate") { id: String, promise: Promise ->
            sinks.beginDeflate(id, promise.sinkReply())
        }
        AsyncFunction("sinkEndDeflate") { id: String, promise: Promise -> sinks.endDeflate(id, promise.sinkReply()) }
        AsyncFunction("sinkCommit") { id: String, name: String, promise: Promise ->
            sinks.commit(id, name, promise.sinkReply())
        }
        AsyncFunction("sinkAbort") { id: String, promise: Promise -> sinks.abort(id, promise.sinkReply()) }
        AsyncFunction("sinkPurge") { kind: String, promise: Promise -> sinks.purge(kind, promise.sinkReply()) }
        AsyncFunction("share") { file: Map<String, Any?>, promise: Promise ->
                try {
                    promise.resolve(ExportSharing.share(appContext.currentActivity, file))
                } catch (error: Exception) {
                    promise.rejectSink(ExportSinks.rejection(error))
                }
            }
            .runOnQueue(Queues.MAIN)
    }
}
