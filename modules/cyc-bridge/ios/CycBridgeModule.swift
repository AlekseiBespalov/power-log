import ExpoModulesCore
import Foundation

private final class BridgeException: Exception {
  private let bridgeCode: String
  private let message: String
  init(code: String, message: String) {
    bridgeCode = code
    self.message = message
    super.init()
  }
  override var code: String { bridgeCode }
  override var reason: String { message }
}

private func rejectRead(_ promise: Promise, _ error: Error) {
  if let storage = error as? PowerLogStorageError, let code = storage.bridgeCode {
    promise.reject(BridgeException(code: code, message: storage.localizedDescription))
  } else if error is CycError || error is WorkoutDataError {
    promise.reject(BridgeException(code: "E_WORKOUT", message: error.localizedDescription))
  } else { promise.reject(error) }
}

struct CatalogPageOptions: Record {
  @Field var limit: Int = 100
  @Field var beforeStartedAt: String?
  @Field var beforeID: String = ""
}

struct CycConnectOptions: Record {
  @Field var deviceId: String = ""
  @Field var hz: Double = 2
}

struct WorkoutStartOptions: Record {
  @Field var indoor: Bool = false
  @Field var useWatch: Bool = true
  @Field var saveToHealth: Bool = true
  @Field var recordGPS: Bool?
  @Field var sampleHz: Double?
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
  var request: MonitorRequest {
    MonitorRequest(source: source, id: id, generation: generation, expectedRevision: expectedRevision,
      sinceRevision: sinceRevision, startSeconds: startSeconds, endSeconds: endSeconds, seconds: seconds,
      metrics: metrics, buckets: buckets, pixelWidth: pixelWidth, includeEndpoints: includeEndpoints,
      anchor: anchor?.anchor, startAnchor: startAnchor?.anchor, endAnchor: endAnchor?.anchor, distanceSource: distanceSource)
  }
}

public final class CycBridgeModule: Module {
  private let observerID = UUID()

  public func definition() -> ModuleDefinition {
    let engine = CycEngine.shared
    Name("CycBridge")
    Events("onDevice", "onState", "onSample", "onWorkoutState")

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
    AsyncFunction("startMonitorRasterFrameDiagnostics") { MonitorRasterFrameDiagnostics.shared.start() }.runOnQueue(.main)
    AsyncFunction("getMonitorRasterFrameDiagnostics") { MonitorRasterFrameDiagnostics.shared.snapshot() }.runOnQueue(.main)
    AsyncFunction("stopMonitorRasterFrameDiagnostics") { MonitorRasterFrameDiagnostics.shared.stop() }.runOnQueue(.main)

    AsyncFunction("describeMonitorSource") { (options: MonitorReadOptions, promise: Promise) in
      MonitorDataStore.shared.submit(.describe, request: options.request) { result in
        switch result {
        case .success(let value): promise.resolve(value)
        case .failure(let error): rejectRead(promise, error)
        }
      }
    }
    AsyncFunction("readMonitorPlot") { (options: MonitorReadOptions, promise: Promise) in
      MonitorDataStore.shared.submit(.plot, request: options.request) { result in
        switch result {
        case .success(let value): promise.resolve(value)
        case .failure(let error): rejectRead(promise, error)
        }
      }
    }
    AsyncFunction("readMonitorLatest") { (options: MonitorReadOptions, promise: Promise) in
      MonitorDataStore.shared.submit(.latest, request: options.request) { result in
        switch result {
        case .success(let value): promise.resolve(value)
        case .failure(let error): rejectRead(promise, error)
        }
      }
    }
    AsyncFunction("inspectMonitorAt") { (options: MonitorReadOptions, promise: Promise) in
      MonitorDataStore.shared.submit(.inspect, request: options.request) { result in
        switch result {
        case .success(let value): promise.resolve(value)
        case .failure(let error): rejectRead(promise, error)
        }
      }
    }
    AsyncFunction("readMonitorRangeStats") { (options: MonitorReadOptions, promise: Promise) in
      MonitorDataStore.shared.submit(.stats, request: options.request) { result in
        switch result {
        case .success(let value): promise.resolve(value)
        case .failure(let error): rejectRead(promise, error)
        }
      }
    }
    AsyncFunction("monitorChangesSince") { (options: MonitorReadOptions, promise: Promise) in
      MonitorDataStore.shared.submit(.changes, request: options.request) { result in
        switch result {
        case .success(let value): promise.resolve(value)
        case .failure(let error): rejectRead(promise, error)
        }
      }
    }

    OnCreate { [weak self] in
      guard let self else { return }
      let id = self.observerID
      if #available(iOS 26.0, *) {
        let workout = WorkoutEngine.shared
        workout.queue.async { [weak self] in
          workout.addSink(id: id) { [weak self] body in
            DispatchQueue.main.async { [weak self] in self?.sendEvent("onWorkoutState", body) }
          }
        }
      }
      engine.queue.async { [weak self] in
        engine.addSink(id: id) { [weak self] event, body in
          // Events carry copies; Bluetooth and disk work stay on the native serial queue.
          DispatchQueue.main.async { [weak self] in self?.sendEvent(event, body) }
        }
      }
    }
    OnDestroy { [weak self] in
      guard let id = self?.observerID else { return }
      engine.queue.async { engine.removeSink(id: id) }
      if #available(iOS 26.0, *) {
        let workout = WorkoutEngine.shared
        workout.queue.async { workout.removeSink(id: id) }
      }
    }

    AsyncFunction("getState") { () -> [String: Any] in engine.state() }.runOnQueue(engine.queue)
    AsyncFunction("getDiagnostics") { () -> [String: Any] in engine.diagnostics() }.runOnQueue(engine.queue)
    AsyncFunction("readDiagnostics") { (promise: Promise) in
      engine.readDiagnostics { result in
        switch result {
        case .success(let jsonl): promise.resolve(jsonl)
        case .failure(let error): promise.reject(error)
        }
      }
    }.runOnQueue(engine.queue)
    AsyncFunction("startScan") { () throws in try engine.startScan() }.runOnQueue(engine.queue)
    AsyncFunction("stopScan") { () in engine.stopScan() }.runOnQueue(engine.queue)
    AsyncFunction("connect") { (options: CycConnectOptions) throws in
      try engine.connect(deviceID: options.deviceId, rate: options.hz)
    }.runOnQueue(engine.queue)
    AsyncFunction("disconnect") { () throws in try engine.disconnect() }.runOnQueue(engine.queue)

    AsyncFunction("getWorkoutState") { (promise: Promise) in
      if #available(iOS 26.0, *) {
        let workout = WorkoutEngine.shared
        workout.queue.async { promise.resolve(workout.state()) }
      } else {
        promise.resolve(["supported": false, "phase": "unavailable", "error": "Complete workout recording requires iOS 26 or newer."])
      }
    }
    AsyncFunction("getWorkoutPermissions") { (promise: Promise) in
      if #available(iOS 26.0, *) {
        WorkoutEngine.shared.getPermissions { result in
          switch result { case .success(let state): promise.resolve(state); case .failure(let error): promise.reject(error) }
        }
      } else { promise.reject(CycError.invalid("Complete workout recording requires iOS 26 or newer.")) }
    }
    AsyncFunction("requestWorkoutPermissionsForOptions") { (options: WorkoutStartOptions, promise: Promise) in
      if #available(iOS 26.0, *) {
        WorkoutEngine.shared.requestPermissions(indoor: options.indoor, useWatch: options.useWatch, saveToHealth: options.saveToHealth, recordGPS: options.recordGPS) { result in
          switch result { case .success(let state): promise.resolve(state); case .failure(let error): promise.reject(error) }
        }
      } else { promise.reject(CycError.invalid("Complete workout recording requires iOS 26 or newer.")) }
    }
    AsyncFunction("requestWorkoutPermissions") { (promise: Promise) in
      if #available(iOS 26.0, *) {
        WorkoutEngine.shared.requestPermissions { result in
          switch result { case .success(let state): promise.resolve(state); case .failure(let error): promise.reject(error) }
        }
      } else { promise.reject(CycError.invalid("Complete workout recording requires iOS 26 or newer.")) }
    }
    AsyncFunction("startWorkout") { (options: WorkoutStartOptions, promise: Promise) in
      if #available(iOS 26.0, *) {
        let workout = WorkoutEngine.shared
        workout.queue.async {
          workout.start(indoor: options.indoor, useWatch: options.useWatch, saveToHealth: options.saveToHealth, recordGPS: options.recordGPS, sampleHz: options.sampleHz) { result in
            switch result { case .success(let state): promise.resolve(state); case .failure(let error): promise.reject(error) }
          }
        }
      } else { promise.reject(CycError.invalid("Complete workout recording requires iOS 26 or newer.")) }
    }
    AsyncFunction("recoverWorkout") { (id: String, promise: Promise) in
      if #available(iOS 26.0, *) {
        let workout = WorkoutEngine.shared
        workout.queue.async { workout.recover(id) { result in
          switch result { case .success(let state): promise.resolve(state); case .failure(let error): promise.reject(error) }
        } }
      } else { promise.reject(CycError.invalid("Complete workout recording requires iOS 26 or newer.")) }
    }
    AsyncFunction("pauseWorkout") { (id: String?, promise: Promise) in
      if #available(iOS 26.0, *) { self.workoutAction(promise) { try $0.pause(expectedID: id) } } else { promise.reject(CycError.invalid("Complete workout recording requires iOS 26 or newer.")) }
    }
    AsyncFunction("resumeWorkout") { (id: String?, promise: Promise) in
      if #available(iOS 26.0, *) { self.workoutAction(promise) { try $0.resume(expectedID: id) } } else { promise.reject(CycError.invalid("Complete workout recording requires iOS 26 or newer.")) }
    }
    AsyncFunction("markWorkoutLap") { (id: String?, promise: Promise) in
      if #available(iOS 26.0, *) { self.workoutAction(promise) { try $0.lap(expectedID: id) } } else { promise.reject(CycError.invalid("Complete workout recording requires iOS 26 or newer.")) }
    }
    AsyncFunction("stopWorkout") { (id: String?, promise: Promise) in
      if #available(iOS 26.0, *) { self.workoutAction(promise) { try $0.stop(expectedID: id) } } else { promise.reject(CycError.invalid("Complete workout recording requires iOS 26 or newer.")) }
    }
    AsyncFunction("discardWorkout") { (id: String, promise: Promise) in
      if #available(iOS 26.0, *) { self.workoutAction(promise) { try $0.stop(expectedID: id, discard: true) } } else { promise.reject(CycError.invalid("Complete workout recording requires iOS 26 or newer.")) }
    }
    AsyncFunction("deleteWorkout") { (id: String, promise: Promise) in
      if #available(iOS 26.0, *) { self.workoutAction(promise) { try $0.deleteWorkout(id) } } else { promise.reject(CycError.invalid("Complete workout recording requires iOS 26 or newer.")) }
    }
    AsyncFunction("listWorkouts") { (options: CatalogPageOptions?, promise: Promise) in
      if #available(iOS 26.0, *) { self.workoutAction(promise) { try $0.list(limit: options?.limit ?? 100, beforeStartedAt: options?.beforeStartedAt, beforeID: options?.beforeID ?? "") } } else { promise.reject(CycError.invalid("Complete workout recording requires iOS 26 or newer.")) }
    }
    #if DEBUG
    AsyncFunction("addExampleRides") { (promise: Promise) in
      if #available(iOS 26.0, *) { self.workoutAction(promise) { try $0.addExampleRides() } } else { promise.reject(CycError.invalid("Complete workout recording requires iOS 26 or newer.")) }
    }
    #endif
    AsyncFunction("readWorkout") { (id: String, distanceSource: String?, promise: Promise) in
      if #available(iOS 26.0, *) { self.workoutFileAction(promise) { try $0.read(id, distanceSource: distanceSource ?? "auto") } } else { promise.reject(CycError.invalid("Complete workout recording requires iOS 26 or newer.")) }
    }
    AsyncFunction("exportWorkoutArchive") { (id: String, promise: Promise) in
      if #available(iOS 26.0, *) { self.workoutFileAction(promise) { try $0.exportOriginal(id) } } else { promise.reject(CycError.invalid("Complete workout recording requires iOS 26 or newer.")) }
    }
    AsyncFunction("exportWorkout") { (id: String, distanceSource: String?, promise: Promise) in
      if #available(iOS 26.0, *) { self.workoutFileAction(promise) { try $0.export(id, distanceSource: distanceSource ?? "auto") } } else { promise.reject(CycError.invalid("Complete workout recording requires iOS 26 or newer.")) }
    }
  }

  @available(iOS 26.0, *)
  private func workoutAction(_ promise: Promise, action: @escaping (WorkoutEngine) throws -> Any) {
    let engine = WorkoutEngine.shared
    engine.queue.async { do { promise.resolve(try action(engine)) } catch { rejectRead(promise, error) } }
  }

  @available(iOS 26.0, *)
  private func workoutFileAction(_ promise: Promise, action: @escaping (WorkoutEngine) throws -> Any) {
    let engine = WorkoutEngine.shared
    let deadline = ProcessInfo.processInfo.systemUptime + 60
    func attempt() {
      engine.fileQueue.async {
        do { promise.resolve(try action(engine)) }
        catch WorkoutDistanceError.pending where ProcessInfo.processInfo.systemUptime < deadline {
          engine.fileQueue.asyncAfter(deadline: .now() + 0.25) { attempt() }
        } catch WorkoutDistanceError.expired where ProcessInfo.processInfo.systemUptime < deadline {
          engine.fileQueue.asyncAfter(deadline: .now() + 0.25) { attempt() }
        } catch { rejectRead(promise, error) }
      }
    }
    attempt()
  }
}
