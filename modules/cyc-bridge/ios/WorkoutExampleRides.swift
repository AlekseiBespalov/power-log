import Foundation

/// Fictional rides written through the production archive writer. Every payload carries `exampleData`
/// and every source seal carries generated provenance, so the interface and exports can identify them.
enum WorkoutExampleRides {
  static let provenance = "generatedExample"
  static let durations = [4356, 2838, 5580]
  static let epoch = "2026-09-06T07:30:00.000Z"
  static func id(_ index: Int) -> String { String(format: "e8a00000-0000-4000-8000-%012d", index + 1) }

  /// Writes the rides that do not exist yet and returns one report per written ride.
  @discardableResult
  static func write(archive: WorkoutArchive, durations: [Int] = durations) throws -> [[String: Any]] {
    let transfer = WorkoutTransferJournal(archive: archive)
    let epoch = try WorkoutCoding.date(Self.epoch)
    var reports: [[String: Any]] = []
    for (rideIndex, duration) in durations.enumerated() {
      let id = Self.id(rideIndex)
      if (try? archive.metadata(id: id)) != nil { continue }
      let start = epoch.addingTimeInterval(-Double(rideIndex) * 172800)
      let ride = try archive.create(id: id, startedAt: start, indoor: false, watchEnabled: true, example: true)
      let hz = 4
      var batch: [WorkoutEvent] = []
      var distance = 0.0, wh = 0.0, ah = 0.0, mechanicalJoules = 0.0, heart = 105.0
      var latitude = 46.50, longitude = 11.35
      var previousHeading = 0.0, maxPower = 0.0
      func flush() throws {
        if !batch.isEmpty { try archive.appendBatch(batch); batch.removeAll(keepingCapacity: true) }
      }
      batch.append(try WorkoutEvent(workoutId: id, kind: "lifecycle", source: "watch", timestamp: start, elapsedSeconds: 0,
        payload: ["action": .string("start"), "indoor": .bool(false), "subSport": .string("e_biking")]))
      for index in 0...duration * hz {
        try autoreleasepool {
          let t = Double(index) / Double(hz), dt = 1.0 / Double(hz)
          let hill = max(0, sin((t - 420) / 325))
          let tempo = 0.5 + 0.5 * sin(t / 112)
          let surge = pow(max(0, sin(t / 43)), 8)
          let coasting = (t.truncatingRemainder(dividingBy: 660) > 585 && t.truncatingRemainder(dividingBy: 660) < 604)
          let warmup = min(1, 0.50 + t / 600)
          let effort = max(0, (88 + 55 * hill + 28 * tempo + 71 * surge + 4 * sin(t * 1.7)) * warmup)
          let power = coasting ? 0 : effort.rounded()
          let cadence = coasting ? 0 : (74 + 10 * tempo - 5 * hill + 3 * sin(t / 17)).rounded()
          let motor = coasting ? 0 : max(0, 135 + 270 * hill + 100 * tempo + 50 * surge + 12 * sin(t / 4))
          let speed = 6.05 + 0.7 * sin(t / 170) - 0.5 * hill + (coasting ? 0.45 : 0)
          let voltage = 56.6 - 3.1 * t / Double(duration) - motor / 680
          let current = motor / voltage
          if index > 0 { distance += speed * dt; wh += motor * dt / 3600; ah += current * dt / 3600; mechanicalJoules += power * dt }
          let heading = 2 * Double.pi * t / Double(duration) + 0.48 * sin(t / 245) + 0.14 * sin(t / 54)
          if index > 0 {
            latitude += speed * dt * cos(previousHeading) / 111320
            longitude += speed * dt * sin(previousHeading) / (111320 * cos(latitude * .pi / 180))
          }
          previousHeading = heading
          heart += ((109 + power * 0.19 + hill * 4) - heart) * dt / 28
          let torque = cadence > 0 ? power / (cadence * 2 * .pi / 60) : 0
          maxPower = max(maxPower, power)
          let payload: [String: WorkoutJSON] = ["humanPowerW": .number(power), "cadenceRpm": .number(cadence),
            "motorInputPowerW": .number(motor), "batteryVoltageV": .number(voltage), "batteryCurrentA": .number(current),
            "motorCurrentA": .number(current * 1.65), "motorRpm": .number((cadence * 22).rounded()),
            "pedalTorqueNm": .number(torque), "controllerTempC": .number(24 + 12 * (1 - exp(-t / 820)) + hill * 2),
            "motorTempC": .number(24 + 25 * (1 - exp(-t / 730)) + hill * 7), "consumedAh": .number(ah), "consumedWh": .number(wh),
            "throttleVoltageV": .number(0.83), "faultCode": .integer(0), "assistLevel": .integer(hill > 0.55 ? 3 : 2),
            "raceMode": .integer(0), "speedRaw": .number(speed * 3.6), "exampleData": .bool(true)]
          batch.append(try WorkoutEvent(workoutId: id, kind: "telemetry", source: "cyc", timestamp: start.addingTimeInterval(t), elapsedSeconds: t, payload: payload))
          if index % hz == 0 {
            batch.append(try WorkoutEvent(workoutId: id, kind: "health", source: "watch", timestamp: start.addingTimeInterval(t), elapsedSeconds: t,
              payload: ["heartRateBpm": .number(heart.rounded()), "activeEnergyKcal": .number(mechanicalJoules / (4184 * 0.24)),
                "basalEnergyKcal": .number(t / 3600 * 63), "distanceMeters": .number(distance), "representation": .string("aggregate"), "exampleData": .bool(true)]))
            batch.append(try WorkoutEvent(workoutId: id, kind: "location", source: "watch", timestamp: start.addingTimeInterval(t), elapsedSeconds: t,
              payload: ["latitude": .number(latitude), "longitude": .number(longitude), "speedMps": .number(speed),
                "altitudeMeters": .number(480 + 78 * sin((t - 420) / 650) + 17 * sin(t / 125)),
                "horizontalAccuracyM": .number(3.2), "verticalAccuracyM": .number(4.1),
                "courseDegrees": .number(heading.truncatingRemainder(dividingBy: 2 * .pi) * 180 / .pi), "exampleData": .bool(true)]))
          }
          if index == duration * hz / 2 {
            batch.append(try WorkoutEvent(workoutId: id, kind: "lifecycle", source: "watch", timestamp: start.addingTimeInterval(t), elapsedSeconds: t, payload: ["action": .string("lap")]))
          }
          if batch.count >= 240 { try flush() }
        }
      }
      batch.append(try WorkoutEvent(workoutId: id, kind: "lifecycle", source: "watch", timestamp: start.addingTimeInterval(Double(duration)), elapsedSeconds: Double(duration), payload: ["action": .string("stop")]))
      try flush()
      _ = try archive.update(id: ride.id, healthKitState: "notSaved", warnings: [], watchSyncState: "received", stopElapsedSeconds: Double(duration))
      _ = try archive.finish(id: id, endedAt: start.addingTimeInterval(Double(duration)))
      var seals: [WorkoutSourceSeal] = []
      for producer in ["cyc", "watch"] {
        try transfer.register(id: id, producer: producer)
        var source = try transfer.source(id: id, producer: producer); source.provenance = provenance; seals.append(source)
      }
      _ = try transfer.accept(seal: WorkoutSeal(workoutID: id, sealRevision: 1, collectionRevision: archive.revision(id: id), ownerRevision: 1,
        stopCutoff: WorkoutCoding.timestamp(start.addingTimeInterval(Double(duration))), healthOutcome: "notSaved", requirements: ["example": "sealed"], sources: seals, stopElapsedSeconds: Double(duration)))
      guard try transfer.verify(id: id) else { throw WorkoutDataError.invalid("Example ride seal did not verify") }
      let summary = try WorkoutFIT.summarize(archive: archive, id: id)
      reports.append(["id": id, "summary": summary.dictionary, "maximumPowerW": maxPower, "batteryConsumedWh": wh, "fictional": true])
    }
    try archive.store.checkpoint()
    return reports
  }
}
