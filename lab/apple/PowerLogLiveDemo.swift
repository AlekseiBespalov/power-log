import Foundation
import PowerLogCore
@testable internal import PowerLogBridge

/// A simulator-only sensor source. Start, controls, recording, charts and export remain the normal app flow.
enum PowerLogLiveDemo {
  static var enabled: Bool {
    #if targetEnvironment(simulator)
      UserDefaults.standard.bool(forKey: "PowerLogLiveDemo")
    #else
      false
    #endif
  }
  private static var timer: DispatchSourceTimer?
  private static let origin = ProcessInfo.processInfo.systemUptime
  private static var previous = 0.0
  private static var wh = 71.1
  private static var ah = 1.3
  private static var sequence = 0
  private static var sensorArchive: WorkoutArchive?
  private static var sensorRide: String?
  private static var sensorPrevious = 0.0
  private static var sensorMeters = 0.0
  private static var sensorKcal = 0.0
  private static var allMetrics: Bool { UserDefaults.standard.bool(forKey: "PowerLogDemoAllMetrics") }

  static func prepare() {
    do {
      let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("power-log-preferences")
      try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
      // The all-metrics capture explicitly resets this isolated Lab layout; ordinary launches retain changes.
      let files = try FileManager.default.contentsOfDirectory(atPath: folder.path)
      guard files.isEmpty || allMetrics else { return }
      var preferences =
        try JSONSerialization.jsonObject(
          with: Data(Preferences.shared.encode(preferences: Preferences.shared.defaults()).utf8)) as! [String: Any]
      preferences["sampleHz"] = 8
      preferences["distanceSource"] = "controller"
      preferences["workoutOptions"] = [
        "indoor": !allMetrics, "useWatch": false, "saveToHealth": false, "recordGPS": allMetrics,
      ]
      if allMetrics {
        let metrics = MetricCatalog.shared.all.map(\.id).filter { $0 != "speedRaw" && $0 != "basalEnergyKcal" }
        var views = preferences["views"] as! [String: [String: Any]]
        views["ride"]?["numbers"] = metrics
        views["ride"]?["charts"] = metrics
        views["ride"]?["range"] = "all"
        preferences["views"] = views
      }
      let revision =
        files.compactMap { name -> UInt64? in
          guard name.hasPrefix("monitor-"), name.hasSuffix(".json") else { return nil }
          return UInt64(name.dropFirst(8).dropLast(5))
        }.max() ?? 0
      try JSONSerialization.data(withJSONObject: preferences).write(
        to: folder.appendingPathComponent(String(format: "monitor-%016llu.json", revision + 1)), options: .atomic)
    } catch { fatalError("Could not prepare the isolated demo: \(error)") }
  }

  static func start() {
    guard enabled, timer == nil else { return }
    let engine = ControllerEngine.shared
    engine.queue.async {
      engine.labBeginCapture()
      let source = DispatchSource.makeTimerSource(queue: engine.queue)
      source.schedule(deadline: .now(), repeating: 0.125, leeway: .milliseconds(5))
      source.setEventHandler {
        do {
          let t = ProcessInfo.processInfo.systemUptime - origin
          guard t < 900 else {
            source.cancel()
            return
          }
          let coast = t.truncatingRemainder(dividingBy: 34) > 29
          let surge = pow(max(0, sin(t / 4.5)), 4)
          let rider = coast ? 0 : 125 + 55 * sin(t / 6) + 100 * surge + 6 * sin(t * 2.1)
          let cadence = coast ? 0 : 78 + 9 * sin(t / 8) + 3 * sin(t * 1.3)
          let batteryPower = coast ? 0 : 240 + 100 * sin(t / 7) + 260 * surge
          let voltage = 55.4 - t * 0.001 - batteryPower / 1400
          let current = batteryPower / voltage
          let dt = min(0.5, max(0, t - previous))
          previous = t
          wh += batteryPower * dt / 3600
          ah += current * dt / 3600
          let speed = 6.3 + 1.2 * sin(t / 12)
          let values: [String: Double] = [
            "humanPowerW": rider, "cadenceRpm": cadence, "motorInputPowerW": batteryPower,
            "batteryVoltageV": voltage, "batteryCurrentA": current, "motorCurrentA": current * 1.7,
            "consumedWh": wh, "consumedAh": ah, "controllerSpeedMps": speed, "speedRaw": speed * 3.6,
            "pedalTorqueNm": cadence > 0 ? rider / (cadence * 2 * .pi / 60) : 0,
            "motorRpm": cadence * 22, "motorTempC": 25 + 14 * (1 - exp(-t / 90)),
            "controllerTempC": 24 + 7 * (1 - exp(-t / 120)), "assistLevel": 2, "faultCode": 0,
            "throttleVoltageV": 0.83, "raceMode": 0,
          ]
          // Use the controller's wire precision so originals also pass normal History/export restoration.
          var metrics = Dictionary(
            uniqueKeysWithValues: CycProtocol.shared.fields.map { field in
              let value = values[field.metric.name]!
              return (field.metric.name, KotlinDouble(value: floor(value * field.divisor + 0.5) / field.divisor))
            })
          metrics["motorInputPowerW"] = KotlinDouble(
            value: floor(
              metrics["batteryVoltageV"]!.doubleValue * metrics["batteryCurrentA"]!.doubleValue * 10000 + 0.5)
              / 10000)
          metrics["controllerSpeedMps"] = KotlinDouble(value: metrics["speedRaw"]!.doubleValue / 3.6)
          let identity = try CycProtocol.shared.restoreIdentity(
            controllerModel: "X6", firmwareLabel: "20260101", controllerProtocol: "5.3")
          engine.labReceive(try CycProtocol.shared.restoreReading(metrics: metrics, identity: identity))
          sequence += 1
          if sequence % 8 == 0 {
            let count = sequence
            WorkoutEngine.shared.queue.async {
              var evidence = WorkoutEngine.shared.state()
              if allMetrics {
                do { try appendSensors(state: evidence, values: values) } catch {
                  fatalError("Simulated sensors failed: \(error)")
                }
              }
              evidence["simulated"] = true
              evidence["generatedSamples"] = count
              evidence["latestValues"] = values
              let path = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("live-demo.json")
              try? JSONSerialization.data(withJSONObject: evidence, options: [.sortedKeys]).write(
                to: path, options: .atomic)
            }
          }
        } catch { fatalError("Simulated telemetry failed: \(error)") }
      }
      timer = source
      source.resume()
    }
  }

  /// Fictional route and physiology enter the same local archive, without writing to HealthKit.
  /// Called only on the workout executor, after the UI has started a ride.
  private static func appendSensors(state: [String: Any], values: [String: Double]) throws {
    guard state["phase"] as? String == "running", let id = state["id"] as? String else { return }
    if sensorArchive == nil { sensorArchive = try WorkoutArchive(rootURL: PowerLogLab.archiveRoot) }
    guard let archive = sensorArchive, let anchor = archive.store.liveEvidence.timeline(id: id) else { return }
    let now = ProcessInfo.processInfo.systemUptime
    let elapsed = max(0, now - anchor.monotonicOrigin)
    let record = try archive.metadata(id: id)
    let start = try WorkoutCoding.date(record.startedAt)
    let date = start.addingTimeInterval(elapsed)
    if sensorRide != id {
      sensorRide = id
      sensorPrevious = elapsed
      sensorMeters = 0
      sensorKcal = 0
    }
    let dt = min(1.5, max(0, elapsed - sensorPrevious))
    sensorPrevious = elapsed
    let speed = values["controllerSpeedMps"]! * (1 + 0.025 * sin(elapsed / 11))
    sensorMeters += speed * dt
    sensorKcal += values["humanPowerW"]! * dt / (4184 * 0.24)
    let heading = 35 + 20 * sin(elapsed / 45)
    let location = try WorkoutEvent(
      workoutId: id, kind: "location", source: "phone", timestamp: date, elapsedSeconds: elapsed,
      payload: [
        "latitude": .number(46.50 + sensorMeters * 0.8 / 111320),
        "longitude": .number(11.35 + sensorMeters * 0.6 / (111320 * cos(46.50 * .pi / 180))),
        "speedMps": .number(speed), "altitudeMeters": .number(480 + 12 * sin(elapsed / 28)),
        "horizontalAccuracyM": .number(3.2 + 0.4 * sin(elapsed / 13)),
        "verticalAccuracyM": .number(4.1 + 0.5 * sin(elapsed / 17)),
        "courseDegrees": .number(heading), "exampleData": .bool(true),
      ])
    try archive.store.appendBatch([location], liveAcquisitions: [location.eventId: now])
    for (key, identifier, unit, value, cumulative) in [
      ("heartRateBpm", "HKQuantityTypeIdentifierHeartRate", "bpm", 126 + 17 * sin(elapsed / 20), false),
      ("activeEnergyKcal", "HKQuantityTypeIdentifierActiveEnergyBurned", "kcal", sensorKcal, true),
    ] {
      let representation = cumulative ? "cumulativeWorkoutTotal" : "builderMostRecent"
      let input = try WorkoutHealthObservation.input(
        start: cumulative ? start : date, end: date, metric: identifier, unit: unit, value: value)
      let identity = try WorkoutHealthObservation.builderID(id: id, input: input, representation: representation)
      let event = try WorkoutEvent(
        workoutId: id, kind: "health", source: "phone", timestamp: date, elapsedSeconds: elapsed,
        payload: [
          "healthKitIdentifier": .string(identifier), "value": .number(value), "unit": .string(unit),
          key: .number(value), "representation": .string(representation), "exampleData": .bool(true),
          "sampleStart": .string(input.originalStart), "sampleEnd": .string(input.originalEnd),
        ], eventId: identity)
      _ = try WorkoutHealthObservation.append(event, archive: archive, dataOrigin: "app.powerlog.lab.synthetic")
      archive.store.liveEvidence.sensor(event, identity: try PowerLogStore.physicalIdentity(event), acquiredAt: now)
    }
  }
}
