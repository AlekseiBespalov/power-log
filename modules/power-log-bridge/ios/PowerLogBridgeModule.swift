import CoreBluetooth
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

private func exportCall<T>(_ body: () throws -> T) throws -> T {
  do { return try body() } catch let failure as ExportFailure {
    throw BridgeException(code: failure.code, message: failure.message)
  } catch let failure as BridgeException { throw failure } catch { throw bridgeFailure(error) }
}

private func bridged<T>(stage: String? = nil, _ body: () throws -> T) throws -> T {
  do { return try body() } catch { throw bridgeFailure(error, stage: stage) }
}

private func bridgeFailure(_ error: Error, stage: String? = nil) -> BridgeException {
  let failure = WorkoutRideFailure.classify(error, stage: stage)
  let code = (error as? WorkoutAnalysisError)?.bridgeCode ?? readErrorCode(error) ?? failure.code
  let message = (error as? MonitorError)?.bridgeMessage ?? failure.message
  return BridgeException(code: code, message: message)
}

private func diagnosticDevice() -> [String: Any] {
  var system = utsname()
  uname(&system)
  let model = withUnsafeBytes(of: system.machine) { String(decoding: $0.prefix { $0 != 0 }, as: UTF8.self) }
  let version = ProcessInfo.processInfo.operatingSystemVersion
  let authorization: String
  switch CBManager.authorization {
  case .allowedAlways: authorization = "allowedAlways"
  case .denied: authorization = "denied"
  case .restricted: authorization = "restricted"
  case .notDetermined: authorization = "notDetermined"
  @unknown default: authorization = "unknown"
  }
  return [
    "manufacturer": "Apple", "model": model,
    "osVersion": "\(version.majorVersion).\(version.minorVersion).\(version.patchVersion)",
    "powerSave": ProcessInfo.processInfo.isLowPowerModeEnabled, "bluetoothAuthorization": authorization,
  ]
}

private func reject(_ promise: Promise, _ error: Error, stage: String? = nil) {
  promise.reject(bridgeFailure(error, stage: stage))
}

struct CatalogPageOptions: Record {
  @Field var limit: Int = 100
  @Field var beforeStartedAt: String?
  @Field var beforeID: String = ""
}

struct ControllerConnectOptions: Record {
  @Field var deviceId: String = ""
  @Field var hz: Double?
}

struct WorkoutStartOptions: Record {
  @Field(.required) var indoor: Bool = false
  @Field var useWatch: Bool?
  @Field var saveToHealth: Bool?
  @Field var recordGPS: Bool?
}

struct ExternalWorkoutControlOptions: Record {
  @Field(.required) var rideId: String = ""
  @Field(.required) var controlToken: String = ""
  @Field(.required) var expectedPhase: String = ""
  @Field(.required) var action: String = ""

  func request() throws -> ExternalControlRequest {
    try ExternalControlRequest(
      rideId: rideId, controlToken: controlToken, expectedPhase: expectedPhase, action: action)
  }
}

struct MonitorAnchorOptions: Record {
  @Field var metric: String = ""
  @Field var observationId: String = ""
  var anchor: MonitorObservationAnchor { MonitorObservationAnchor(metric: metric, observationId: observationId) }
}

struct MonitorReadOptions: Record {
  @Field var source: String = "live"
  @Field var id: String?
  @Field var generation: Int = 0
  @Field var expectedRevision: String?
  @Field var sinceRevision: String?
  @Field var startSeconds: Double?
  @Field var endSeconds: Double?
  @Field var metrics: [String] = []
  @Field var buckets: Int = 128
  @Field var pixelWidth: Double?
  @Field var seconds: Double?
  @Field var includeEndpoints: Bool = false
  @Field var anchor: MonitorAnchorOptions?
  @Field var startAnchor: MonitorAnchorOptions?
  @Field var endAnchor: MonitorAnchorOptions?
  @Field var distanceSource: String = "auto"
  @Field var energySmoothingSeconds: Int = 0
  @Field var energyTotals: Bool = true
  @Field var energyCurrent: Bool = false
  var request: MonitorRequest {
    MonitorRequest(
      source: source, id: id, generation: generation, expectedRevision: expectedRevision,
      sinceRevision: sinceRevision, startSeconds: startSeconds, endSeconds: endSeconds, seconds: seconds,
      metrics: metrics, buckets: buckets, pixelWidth: pixelWidth, includeEndpoints: includeEndpoints,
      anchor: anchor?.anchor, startAnchor: startAnchor?.anchor, endAnchor: endAnchor?.anchor,
      distanceSource: distanceSource, energySmoothingSeconds: energySmoothingSeconds, energyTotals: energyTotals,
      energyCurrent: energyCurrent)
  }
}

public final class PowerLogBridgeModule: Module {
  private let observerID = UUID()

  public func definition() -> ModuleDefinition {
    let engine = ControllerEngine.shared
    Name("PowerLogBridge")
    Events("onDevice", "onState", "onSample", "onWorkoutState")
    AsyncFunction("getMonotonicSeconds") { ProcessInfo.processInfo.systemUptime }

    View(MonitorRasterView.self) {
      Events("onRenderStatus")
      Prop("sourceId") { (view: MonitorRasterView, value: String) in view.setSourceID(value) }
      Prop("scene") { (view: MonitorRasterView, value: String) in view.setScene(value) }
      Prop("sceneKey") { (view: MonitorRasterView, value: String) in view.setSceneKey(value) }
      Prop("presentation") { (view: MonitorRasterView, value: [Double]) in view.setPresentation(value) }
      Prop("selection") { (view: MonitorRasterView, value: String) in view.setSelection(value) }
      Prop("selectionTarget") { (view: MonitorRasterView, value: String) in view.setSelectionTarget(value) }
    }
    Function("getMonitorRasterDiagnostics") { MonitorRasterWorker.shared.diagnostics() }
    Function("resetMonitorRasterDiagnostics") { MonitorRasterWorker.shared.diagnostics(reset: true) }
    AsyncFunction("startMonitorRasterFrameDiagnostics") { MonitorRasterFrameDiagnostics.shared.start() }.runOnQueue(
      .main)
    AsyncFunction("getMonitorRasterFrameDiagnostics") { MonitorRasterFrameDiagnostics.shared.snapshot() }.runOnQueue(
      .main)
    AsyncFunction("stopMonitorRasterFrameDiagnostics") { MonitorRasterFrameDiagnostics.shared.stop() }.runOnQueue(.main)

    AsyncFunction("describeMonitorSource") { (options: MonitorReadOptions, promise: Promise) in
      MonitorDataStore.shared.submit(.describe, request: options.request) { result in
        switch result {
        case .success(let value): promise.resolve(value)
        case .failure(let error): reject(promise, error)
        }
      }
    }
    AsyncFunction("readMonitorPlot") { (options: MonitorReadOptions, promise: Promise) in
      MonitorDataStore.shared.submit(.plot, request: options.request) { result in
        switch result {
        case .success(let value): promise.resolve(value)
        case .failure(let error): reject(promise, error)
        }
      }
    }
    AsyncFunction("readMonitorLatest") { (options: MonitorReadOptions, promise: Promise) in
      MonitorDataStore.shared.submit(.latest, request: options.request) { result in
        switch result {
        case .success(let value): promise.resolve(value)
        case .failure(let error): reject(promise, error)
        }
      }
    }
    AsyncFunction("inspectMonitorAt") { (options: MonitorReadOptions, promise: Promise) in
      MonitorDataStore.shared.submit(.inspect, request: options.request) { result in
        switch result {
        case .success(let value): promise.resolve(value)
        case .failure(let error): reject(promise, error)
        }
      }
    }
    AsyncFunction("readMonitorRangeStats") { (options: MonitorReadOptions, promise: Promise) in
      MonitorDataStore.shared.submit(.stats, request: options.request) { result in
        switch result {
        case .success(let value): promise.resolve(value)
        case .failure(let error): reject(promise, error)
        }
      }
    }
    AsyncFunction("readMonitorEnergyPerDistance") { (options: MonitorReadOptions, promise: Promise) in
      MonitorDataStore.shared.submit(.energyPerDistance, request: options.request) { result in
        switch result {
        case .success(let value): promise.resolve(value)
        case .failure(let error): reject(promise, error)
        }
      }
    }
    AsyncFunction("monitorChangesSince") { (options: MonitorReadOptions, promise: Promise) in
      MonitorDataStore.shared.submit(.changes, request: options.request) { result in
        switch result {
        case .success(let value): promise.resolve(value)
        case .failure(let error): reject(promise, error)
        }
      }
    }

    OnCreate { [weak self] in
      ExportSinks.shared.queue.async { ExportSinks.shared.cleanUp() }
      guard let self else { return }
      let id = self.observerID
      let workout = WorkoutEngine.shared
      workout.queue.async { [weak self] in
        WorkoutLiveActivity.shared.onStateChange = { [weak self] body in
          DispatchQueue.main.async { [weak self] in self?.sendEvent("onWorkoutState", body) }
        }
        workout.addSink(id: id) { [weak self] body in
          let body = self?.activityState(body) ?? body
          DispatchQueue.main.async { [weak self] in self?.sendEvent("onWorkoutState", body) }
        }
      }
      engine.queue.async { [weak self] in
        engine.addSink(id: id) { [weak self] event, body in
          DispatchQueue.main.async { [weak self] in
            self?.sendEvent(event, body)
          }
        }
      }
    }
    OnDestroy { [weak self] in
      ExportSinks.shared.stop()
      ExportSinks.shared.queue.async { ExportSinks.shared.abortAll() }
      ExportSource.shared.queue.async { ExportSource.shared.closeAll() }
      guard let id = self?.observerID else { return }
      WorkoutSummaryReads.shared.cancel(prefix: id.uuidString + ":")
      engine.queue.async { engine.removeSink(id: id) }
      let workout = WorkoutEngine.shared
      workout.queue.async {
        workout.removeSink(id: id)
        WorkoutLiveActivity.shared.onStateChange = nil
      }
    }

    AsyncFunction("getState") { () -> [String: Any] in engine.state() }.runOnQueue(engine.queue)
    AsyncFunction("getDiagnostics") { () -> [String: Any] in engine.diagnostics() }.runOnQueue(engine.queue)
    AsyncFunction("getBluetoothTrace") { () -> [String: Any] in engine.bluetoothTraceState() }.runOnQueue(engine.queue)
    AsyncFunction("startBluetoothTrace") { engine.startBluetoothTrace() }.runOnQueue(engine.queue)
    AsyncFunction("stopBluetoothTrace") { engine.stopBluetoothTrace() }.runOnQueue(engine.queue)
    AsyncFunction("clearBluetoothTrace") { engine.clearBluetoothTrace() }.runOnQueue(engine.queue)
    AsyncFunction("readBluetoothTrace") { () -> String in engine.readBluetoothTrace() }.runOnQueue(engine.queue)
    AsyncFunction("readConnectionDiagnostics") { (promise: Promise) in
      engine.readDiagnosticLog { result in
        switch result {
        case .success(let log): promise.resolve(["log": log, "device": diagnosticDevice()])
        case .failure(let error): reject(promise, error)
        }
      }
    }
    AsyncFunction("startScan") { () throws in try bridged(stage: "bike") { try engine.startScan() } }.runOnQueue(
      engine.queue)
    AsyncFunction("stopScan") { () in engine.stopScan() }.runOnQueue(engine.queue)
    AsyncFunction("connect") { (options: ControllerConnectOptions) throws in
      try bridged(stage: "bike") { try engine.connect(deviceID: options.deviceId, rate: options.hz ?? 2) }
    }.runOnQueue(engine.queue)
    AsyncFunction("disconnect") { () throws in try bridged(stage: "bike") { try engine.disconnect() } }.runOnQueue(
      engine.queue)

    AsyncFunction("getWorkoutState") { (promise: Promise) in
      let workout = WorkoutEngine.shared
      workout.queue.async { promise.resolve(self.activityState(workout.state())) }
    }
    AsyncFunction("getWorkoutPermissions") { (promise: Promise) in
      WorkoutEngine.shared.getPermissions { result in
        switch result {
        case .success(let state): promise.resolve(state)
        case .failure(let error): reject(promise, error, stage: "health")
        }
      }
    }
    AsyncFunction("requestWorkoutPermissions") { (options: WorkoutStartOptions, promise: Promise) in
      WorkoutEngine.shared.requestPermissions(
        indoor: options.indoor, useWatch: options.useWatch ?? false, saveToHealth: options.saveToHealth ?? true,
        recordGPS: options.recordGPS
      ) { result in
        switch result {
        case .success(let state): promise.resolve(state)
        case .failure(let error): reject(promise, error, stage: "health")
        }
      }
    }
    AsyncFunction("startWorkout") { (options: WorkoutStartOptions, promise: Promise) in
      let workout = WorkoutEngine.shared
      workout.queue.async {
        workout.start(
          indoor: options.indoor, useWatch: options.useWatch ?? false, saveToHealth: options.saveToHealth ?? true,
          recordGPS: options.recordGPS
        ) { result in
          switch result {
          case .success(let state): promise.resolve(state)
          case .failure(let error): reject(promise, error, stage: "start")
          }
        }
      }
    }
    AsyncFunction("refreshWorkoutHealth") { (id: String, promise: Promise) in
      let workout = WorkoutEngine.shared
      workout.queue.async {
        workout.refreshHealth(id) { result in
          switch result {
          case .success(let state): promise.resolve(state)
          case .failure(let error): reject(promise, error)
          }
        }
      }
    }
    AsyncFunction("pauseWorkout") { (id: String?, promise: Promise) in
      self.workoutAction(promise) { try $0.pause(expectedID: id) }
    }
    AsyncFunction("resumeWorkout") { (id: String?, promise: Promise) in
      self.workoutAction(promise) { try $0.resume(expectedID: id) }
    }
    AsyncFunction("markWorkoutLap") { (id: String?, promise: Promise) in
      self.workoutAction(promise) { try $0.lap(expectedID: id) }
    }
    AsyncFunction("stopWorkout") { (id: String?, promise: Promise) in
      self.workoutAction(promise) { try $0.stop(expectedID: id) }
    }
    AsyncFunction("discardWorkout") { (id: String, promise: Promise) in
      self.workoutAction(promise) { try $0.stop(expectedID: id, discard: true) }
    }
    AsyncFunction("finishExternalWorkoutAction") {
      (options: ExternalWorkoutControlOptions, discard: Bool, promise: Promise) in
      self.workoutAction(promise) { engine in
        try WorkoutLiveActivity.shared.controls.finish(try options.request(), discard: discard)
        return engine.state()
      }
    }
    AsyncFunction("cancelExternalWorkoutAction") { (options: ExternalWorkoutControlOptions, promise: Promise) in
      self.workoutAction(promise) { engine in
        WorkoutLiveActivity.shared.controls.cancel(try options.request())
        return engine.state()
      }
    }
    AsyncFunction("deleteWorkout") { (id: String, promise: Promise) in
      self.workoutAction(promise) { try $0.deleteWorkout(id) }
    }
    AsyncFunction("listWorkouts") { (options: CatalogPageOptions?, promise: Promise) in
      self.workoutAction(promise) {
        try $0.list(
          limit: options?.limit ?? 100, beforeStartedAt: options?.beforeStartedAt, beforeID: options?.beforeID ?? "")
      }
    }
    #if DEBUG
      AsyncFunction("addExampleRides") { (promise: Promise) in
        self.workoutAction(promise) { try $0.addExampleRides() }
      }
    #endif
    AsyncFunction("readWorkout") { (id: String, distanceSource: String?, cancellationId: String?, promise: Promise) in
      let prefix = self.observerID.uuidString + ":"
      do {
        try WorkoutEngine.shared.read(
          id, distanceSource: distanceSource ?? "auto",
          requestID: prefix + (cancellationId ?? UUID().uuidString), row: prefix + id
        ) { result in
          switch result {
          case .success(let value): promise.resolve(value)
          case .failure(let error): reject(promise, error)
          }
        }
      } catch { reject(promise, error) }
    }
    AsyncFunction("cancelWorkoutRead") { (cancellationId: String) in
      WorkoutSummaryReads.shared.cancel(self.observerID.uuidString + ":" + cancellationId)
    }

    let source = ExportSource.shared
    let sinks = ExportSinks.shared
    AsyncFunction("exportOpen") { (request: [String: Any]) throws -> [String: Any] in
      try exportCall { try source.open(request) }
    }.runOnQueue(source.queue)
    AsyncFunction("exportPage") { (request: [String: Any]) throws -> [String: Any] in
      try exportCall { try source.page(request) }
    }.runOnQueue(source.queue)
    AsyncFunction("exportClose") { (session: String) in source.close(session) }.runOnQueue(source.queue)
    AsyncFunction("sinkOpen") { (kind: String, _: [String: Any]) throws -> String in
      guard ["fit", "zip", "csv"].contains(kind) else {
        throw BridgeException(code: "unsupported", message: "This export kind is not supported.")
      }
      return try exportCall { try sinks.open() }
    }.runOnQueue(sinks.queue)
    AsyncFunction("sinkWrite") { (id: String, bytes: Data) throws in
      try exportCall { try sinks.write(id, bytes) }
    }.runOnQueue(sinks.queue)
    AsyncFunction("sinkWriteAt") { (id: String, offset: Double, bytes: Data) throws in
      try exportCall { try sinks.write(id, at: offset, bytes) }
    }.runOnQueue(sinks.queue)
    AsyncFunction("sinkBeginDeflate") { (id: String) throws in
      try exportCall { try sinks.beginDeflate(id) }
    }.runOnQueue(sinks.queue)
    AsyncFunction("sinkEndDeflate") { (id: String) throws -> [String: Any] in
      try exportCall { try sinks.endDeflate(id) }
    }.runOnQueue(sinks.queue)
    AsyncFunction("sinkCommit") { (id: String, name: String) throws -> [String: Any] in
      try exportCall { try sinks.commit(id, name: name) }
    }.runOnQueue(sinks.queue)
    AsyncFunction("sinkAbort") { (id: String) in sinks.abort(id) }.runOnQueue(sinks.queue)
  }

  private func workoutAction(_ promise: Promise, action: @escaping (WorkoutEngine) throws -> Any) {
    let engine = WorkoutEngine.shared
    engine.queue.async {
      do {
        let result = try action(engine)
        promise.resolve((result as? [String: Any]).map(self.activityState) ?? result)
      } catch { reject(promise, error) }
    }
  }

  private func activityState(_ body: [String: Any]) -> [String: Any] {
    guard body["phase"] is String else { return body }
    return WorkoutLiveActivity.shared.state(body)
  }

}
