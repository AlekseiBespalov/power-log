import ExpoModulesCore
import Foundation
import PowerLogCore

private final class BridgeException: Exception {
  private let bridgeCode: String
  private let text: String
  init(code: String, message: String) {
    bridgeCode = code
    text = message
    super.init()
  }
  override var code: String { bridgeCode }
  override var reason: String { text }
  // JavaScript receives `String(reflecting:)`, which would add the class name and source location.
  override var debugDescription: String { text }
}

private func sinkCall<T>(_ body: () throws -> T) throws -> T {
  do { return try body() } catch {
    let failure = ExportSinks.failure(error)
    throw BridgeException(code: failure.code, message: failure.message)
  }
}

private final class BridgeSessionMissing: Exception {
  override var reason: String { "The Power Log runtime has no bridge session for this module." }
}

public final class PowerLogModule: Module {
  private let session = SessionBox()
  private lazy var documents = SystemDocumentPicker { [weak self] in
    self?.appContext?.utilities?.currentViewController()
  }

  public func definition() -> ModuleDefinition {
    let session = self.session
    let documents = self.documents
    Name("PowerLog")

    View(ChartRasterView.self) {
      Events("onRenderStatus")
      Prop("sourceId") { (view: ChartRasterView, value: String) in view.setSourceID(value) }
      Prop("scene") { (view: ChartRasterView, value: String) in view.setScene(value) }
      Prop("sceneKey") { (view: ChartRasterView, value: String) in view.setSceneKey(value) }
      Prop("presentation") { (view: ChartRasterView, value: [Double]) in view.setPresentation(value) }
      Prop("selection") { (view: ChartRasterView, value: String) in view.setSelection(value) }
      Prop("selectionTarget") { (view: ChartRasterView, value: String) in view.setSelectionTarget(value) }
    }

    OnCreate { [weak self] in
      ExportSinks.shared.queue.async { ExportSinks.shared.cleanUp() }
      session.open(PowerLogHost.runtime?.openBridgeSession())
      AppleUiContext.shared.attach(session) { [weak self] in self?.appContext?.utilities?.currentViewController() }
      AttachedDocuments.shared.attach(documents)
    }
    OnDestroy {
      session.close()
      AppleUiContext.shared.detach(session)
      AttachedDocuments.shared.detach(documents)
      ExportSinks.shared.stop()
      ExportSinks.shared.queue.async { ExportSinks.shared.abortAll() }
    }

    let sinks = ExportSinks.shared
    AsyncFunction("call") { (op: String, request: String, promise: Promise) in
      guard let bridge = session.current else { return promise.reject(BridgeSessionMissing()) }
      bridge.call(op: op, request: request) { promise.resolve($0) }
    }
    AsyncFunction("next") { (stream: String, request: String, promise: Promise) in
      guard let bridge = session.current else { return promise.reject(BridgeSessionMissing()) }
      bridge.next(stream: stream, request: request) { promise.resolve($0) }
    }
    AsyncFunction("exportPage") { (request: String, promise: Promise) in
      guard let bridge = session.current else { return promise.reject(BridgeSessionMissing()) }
      bridge.exportPage(request: request) { answer in
        promise.resolve([
          "envelope": answer.envelope,
          "numbers": answer.numbers.mapValues { AppleBytes.shared.data(bytes: $0) },
        ])
      }
    }
    AsyncFunction("sinkOpen") { (kind: String) throws -> String in
      try sinkCall { try sinks.open(kind) }
    }.runOnQueue(sinks.queue)
    AsyncFunction("sinkWrite") { (id: String, bytes: Data) throws in
      try sinkCall { try sinks.write(id, bytes) }
    }.runOnQueue(sinks.queue)
    AsyncFunction("sinkWriteAt") { (id: String, offset: Double, bytes: Data) throws in
      try sinkCall { try sinks.write(id, at: offset, bytes) }
    }.runOnQueue(sinks.queue)
    AsyncFunction("sinkBeginDeflate") { (id: String) throws in
      try sinkCall { try sinks.beginDeflate(id) }
    }.runOnQueue(sinks.queue)
    AsyncFunction("sinkEndDeflate") { (id: String) throws -> [String: Any] in
      try sinkCall { try sinks.endDeflate(id) }
    }.runOnQueue(sinks.queue)
    AsyncFunction("sinkCommit") { (id: String, name: String) throws -> [String: Any] in
      try sinkCall { try sinks.commit(id, name: name) }
    }.runOnQueue(sinks.queue)
    AsyncFunction("sinkAbort") { (id: String) in sinks.abort(id) }.runOnQueue(sinks.queue)
    AsyncFunction("sinkPurge") { (kind: String) throws in
      try sinkCall { try sinks.purge(kind) }
    }.runOnQueue(sinks.queue)
    AsyncFunction("share") { [weak self] (file: [String: Any], promise: Promise) in
      ExportSharing.share(file, root: sinks.root, from: self?.appContext?.utilities?.currentViewController()) {
        switch $0 {
        case .success(let outcome): promise.resolve(outcome)
        case .failure(let failure): promise.reject(BridgeException(code: failure.code, message: failure.message))
        }
      }
    }.runOnQueue(.main)
  }

}
