import Foundation
import PowerLogCore

struct ReclaimMeasurement {
  let rideBytes: Int64
  let before: Int64
  let after: Int64
  let steps: Int
  let milliseconds: Double
}

func timedReclaim(_ store: PowerLogStore) -> (outcome: PowerLogStore.ReclaimOutcome, milliseconds: Double) {
  let started = ProcessInfo.processInfo.systemUptime
  let outcome = store.reclaimSpace()
  return (outcome, (ProcessInfo.processInfo.systemUptime - started) * 1000)
}

func reclaimRide(_ archive: WorkoutArchive, seconds: Int, phase: String = "completed") throws -> String {
  let start = Date(timeIntervalSince1970: 1_788_912_000)
  let ride = try archive.create(
    startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false, sampleHz: 8)
  var batch: [WorkoutEvent] = []
  for sample in 0..<(seconds * 8) {
    let elapsed = Double(sample) / 8
    batch.append(
      try WorkoutEvent(
        workoutId: ride.id, kind: "telemetry", source: "controller", timestamp: start.addingTimeInterval(elapsed),
        elapsedSeconds: elapsed,
        payload: [
          "humanPowerW": .number(150 + Double(sample % 97)), "cadenceRpm": .number(80 + Double(sample % 13)),
          "batteryVoltageV": .number(52.4), "batteryCurrentA": .number(Double(sample % 211) / 10),
          "motorInputPowerW": .number(Double(sample % 503)),
        ]))
    if batch.count == 512 {
      _ = try archive.appendBatch(batch)
      batch.removeAll(keepingCapacity: true)
    }
  }
  if !batch.isEmpty { _ = try archive.appendBatch(batch) }
  _ = try archive.update(id: ride.id, phase: phase)
  return ride.id
}

func reclaimPragma(_ store: PowerLogStore, _ name: String) throws -> Int64 {
  try store.read { try $0.scalarInt("PRAGMA \(name)") ?? -1 }
}

func reclaimIntegrity(_ store: PowerLogStore) throws -> Bool {
  try store.read { try $0.rows("PRAGMA integrity_check", limit: 1).first?.string("integrity_check") } == "ok"
}

func truncateWAL(_ store: PowerLogStore) throws {
  _ = try store.read { try $0.rows("PRAGMA wal_checkpoint(TRUNCATE)", limit: 1) }
}

func walBytes(_ store: PowerLogStore) -> Int64? {
  (try? FileManager.default.attributesOfItem(atPath: store.databaseURL.path + "-wal")[.size] as? NSNumber)?.int64Value
}

func deleteForReclaim(_ archive: WorkoutArchive, _ id: String) throws {
  _ = try archive.store.markWorkoutDeleted(id: id)
  while try !archive.cleanupDeletedWorkoutPage(id: id) {}
}

// Rides are added one at a time, so each ride's growth of the checkpointed file is its own pages.
func reclaimRides(_ archive: WorkoutArchive, count: Int, seconds: Int) throws -> [(id: String, bytes: Int64)] {
  var rides: [(id: String, bytes: Int64)] = []
  try truncateWAL(archive.store)
  for _ in 0..<count {
    let before = archive.store.fileBytes
    let id = try reclaimRide(archive, seconds: seconds)
    try truncateWAL(archive.store)
    rides.append((id, archive.store.fileBytes - before))
  }
  return rides
}

func reclaimTests(root: URL) throws -> [String: ReclaimMeasurement] {
  var measurements: [String: ReclaimMeasurement] = [:]

  let created = try WorkoutArchive(rootURL: root.appendingPathComponent("reclaim-incremental"))
  let store = created.store
  check(try reclaimPragma(store, "auto_vacuum") == 2, "a new store reclaims space incrementally")
  check(
    try reclaimPragma(store, "journal_size_limit") == Int64(PowerLogStore.walLimitBytes),
    "the WAL is truncated to a bounded size after checkpoints")
  let rides = try reclaimRides(created, count: 4, seconds: 600)
  let deleted = rides[1]
  let kept = try rides.filter { $0.id != deleted.id }.map { ($0.id, try created.metadata(id: $0.id).eventCount) }
  let before = store.fileBytes
  try deleteForReclaim(created, deleted.id)
  var steps: [StorageReclaimAction] = []
  store.reclaimStepObserverForTesting = { steps.append($0) }
  let (outcome, milliseconds) = timedReclaim(store)
  store.reclaimStepObserverForTesting = nil
  let after = store.fileBytes
  measurements["incremental"] = ReclaimMeasurement(
    rideBytes: deleted.bytes, before: before, after: after, steps: outcome.steps, milliseconds: milliseconds)
  check(outcome.failure == nil && outcome.deferred == nil && !outcome.converted, "an incremental store just reclaims")
  check(
    outcome.steps >= Int(deleted.bytes / StorageReclaim.shared.stepBytes) && steps.last == .done,
    "reclaim runs in bounded steps")
  check(
    Double(before - after) >= 0.85 * Double(deleted.bytes) && Double(before - after) <= 1.1 * Double(deleted.bytes),
    "deleting a ride gives back about its pages: \(before) to \(after) for \(deleted.bytes)")
  check(walBytes(store) == 0, "reclaim truncates the WAL")
  check(try reclaimPragma(store, "freelist_count") == 0, "every free page is returned")
  check(try reclaimPragma(store, "synchronous") == 2, "reclaim keeps FULL synchronous commits")
  check(try reclaimIntegrity(store), "reclaim keeps the store intact")
  for (id, count) in kept {
    check(try created.metadata(id: id).eventCount == count, "reclaim keeps the remaining rides")
  }
  check(store.reclaimSpace().steps == 0, "a second reclaim has nothing to do")

  let converted = try WorkoutArchive(rootURL: root.appendingPathComponent("reclaim-convert"))
  let legacy = converted.store
  try legacy.read { db in
    try db.execute("PRAGMA auto_vacuum=NONE")
    try db.execute("VACUUM")
  }
  check(try reclaimPragma(legacy, "auto_vacuum") == 0, "fixture is a store created without auto_vacuum")
  let legacyRides = try reclaimRides(converted, count: 3, seconds: 300)
  let recording = try reclaimRide(converted, seconds: 10, phase: "running")
  try truncateWAL(legacy)
  let legacyBefore = legacy.fileBytes
  try deleteForReclaim(converted, legacyRides[0].id)
  legacy.availableBytesForTesting = { Int64.max / 4 }
  let whileRecording = legacy.reclaimSpace()
  check(
    whileRecording.deferred == .waitforidle && !whileRecording.converted, "no conversion while a ride is recording")
  check(try reclaimPragma(legacy, "auto_vacuum") == 0, "a deferred conversion leaves the store unchanged")
  _ = try converted.update(id: recording, phase: "completed")
  legacy.availableBytesForTesting = { 0 }
  check(legacy.reclaimSpace().deferred == .waitforspace, "no conversion without room for the copy")
  check(try reclaimPragma(legacy, "auto_vacuum") == 0, "a conversion without space leaves the store unchanged")
  legacy.availableBytesForTesting = { Int64.max / 4 }
  let started = DispatchSemaphore(value: 0)
  var startDuringConversion: Result<WorkoutMetadata, Error>?
  legacy.beforeVacuumForTesting = {
    Thread {
      startDuringConversion = Result {
        try converted.create(
          startedAt: Date(), indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false, sampleHz: 8)
      }
      started.signal()
    }.start()
    while !legacy.foregroundWorkWaiting { usleep(100) }
  }
  let preempted = legacy.reclaimSpace()
  legacy.beforeVacuumForTesting = nil
  check(started.wait(timeout: .now() + 5) == .success, "Start does not wait for a conversion")
  let startedRide = try startDuringConversion!.get()
  check(preempted.deferred == .waitforidle && !preempted.converted, "Start during a conversion defers it")
  check(try reclaimPragma(legacy, "auto_vacuum") == 0 && reclaimIntegrity(legacy), "a preempted conversion rolls back")
  _ = try converted.update(id: startedRide.id, phase: "completed")
  let (conversion, conversionMilliseconds) = timedReclaim(legacy)
  let legacyAfter = legacy.fileBytes
  measurements["conversion"] = ReclaimMeasurement(
    rideBytes: legacyRides[0].bytes, before: legacyBefore, after: legacyAfter, steps: conversion.steps,
    milliseconds: conversionMilliseconds)
  check(conversion.converted && conversion.failure == nil, "an idle store converts on the first reclaim")
  check(try reclaimPragma(legacy, "auto_vacuum") == 2, "the converted store reclaims incrementally")
  check(
    Double(legacyBefore - legacyAfter) >= 0.85 * Double(legacyRides[0].bytes),
    "conversion gives back the deleted ride: \(legacyBefore) to \(legacyAfter) for \(legacyRides[0].bytes)")
  check(walBytes(legacy) == 0, "conversion truncates the WAL")
  check(try reclaimIntegrity(legacy), "conversion keeps the store intact")
  for ride in legacyRides.dropFirst() {
    check(try converted.metadata(id: ride.id).eventCount == 300 * 8, "conversion keeps the remaining rides")
  }
  try deleteForReclaim(converted, legacyRides[1].id)
  let next = legacy.reclaimSpace()
  check(!next.converted && next.steps > 0, "later deletions on a converted store reclaim incrementally")
  return measurements
}
