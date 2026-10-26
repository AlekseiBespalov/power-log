import Foundation
import PowerLogCore

private func activityFixture(_ name: String) throws -> [String: Any] {
  let url = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
    .appendingPathComponent("tests/fixtures/contract/presentation/\(name).json")
  return try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
}

func activitySnapshot(
  rideID: String, token: String, phase: String = "running", pending: String? = nil,
  now: Int64 = 105_000_000, bike: Int64? = 100_000_000, heart: Int64? = 100_000_000,
  epoch: String = "fixture"
) throws -> WorkoutActivitySnapshot {
  if epoch == "fixture" {
    return WorkoutActivitySnapshot(
      rideID: rideID, phase: phase, pendingAction: pending, timerSeconds: 10,
      observedAt: Date(timeIntervalSince1970: Double(now) / 1_000_000), observedUptime: Double(now) / 1_000_000,
      bikeSampleAgeSeconds: bike.map { Double(now - $0) / 1_000_000 },
      heartSampleAgeSeconds: heart.map { Double(now - $0) / 1_000_000 },
      riderPowerW: 200, heartRateBpm: 150, controlToken: token)
  }
  let clock = try PresentationClock(epoch: "fixture", monotonicUs: now, utcUs: now)
  var state = try LivePresentation.shared.adopt(state: LivePresentation.shared.empty(), rideId: rideID)
  for (source, metric, acquired, value) in [
    (LiveSource.controller, "humanPowerW", bike, 200.0),
    (LiveSource.heartrate, "heartRateBpm", heart, 150.0),
  ] {
    guard let acquired else { continue }
    state = try LivePresentation.shared.observe(
      state: state, rideId: rideID, source: source,
      reading: LiveReading(
        acquisition: LiveAcquisition(
          observationId: metric, sequence: nil, originalTimeUs: acquired, epoch: epoch, monotonicUs: acquired),
        values: [metric: KotlinDouble(value: value)]), clock: clock, backfilled: false)
  }
  return WorkoutActivitySnapshot(
    rideID: rideID, phase: phase, pendingAction: pending, timerSeconds: 10, clock: clock,
    readings: state, controlToken: token)
}

func activityPresentationFixtureChecks(_ check: (Bool, String) -> Void) throws {
  let fixture = try activityFixture("activity")
  let ride = fixture["rideA"] as! String
  let token = fixture["token"] as! String
  for (index, row) in (fixture["cases"] as! [[String: Any]]).enumerated() {
    let now = Int64(row["nowUs"] as! String)!
    let snapshot = try activitySnapshot(
      rideID: ride, token: token, phase: row["phase"] as! String, pending: row["pending"] as? String, now: now,
      bike: Int64(fixture["bikeAcquiredUs"] as! String), heart: Int64(fixture["heartAcquiredUs"] as! String))
    let projected = try snapshot.project(sequence: Int64(index + 1))!
    let content = WorkoutActivityProjection.content(projected, at: now)
    check(try JSONEncoder().encode(content).count < 4096, "ActivityKit content remains below four KB")
    let restored = try JSONDecoder().decode(
      PowerLogRideAttributes.ContentState.self, from: JSONEncoder().encode(content))
    let stale = row["systemStale"] as! Bool
    let rendered = restored.render(at: Date(timeIntervalSince1970: Double(now) / 1_000_000), isStale: stale)
    check(
      rendered == WorkoutActivityProjection.render(projected.render(nowUtcUs: now, systemStale: stale)),
      "F34/F47 wire render matches core case \(index)")
    check(rendered.bike.text == row["bike"] as! String, "F47 bike case \(index)")
    check(rendered.heartRate.text == row["heart"] as! String, "F47 heart case \(index)")
    check(rendered.status.text == row["status"] as! String, "F34 status case \(index)")
    check(rendered.controls.map(\.action) == row["controls"] as! [String], "F34 controls case \(index)")
    check(rendered.timer.mode == row["timer"] as! String, "F34 timer case \(index)")
    check(
      restored.staleDate
        == (row["staleDateUs"] as? String).map { Date(timeIntervalSince1970: Double($0)! / 1_000_000) },
      "F47 earliest upcoming staleDate case \(index)")
    check(
      restored.publicationSequence == Int64(index + 1) && restored.rideID == ride && restored.controlToken == token,
      "Wire retains publication and control identity")
    check(
      restored.bikeExpiresAt == Date(timeIntervalSince1970: 121)
        && restored.heartRateExpiresAt == Date(timeIntervalSince1970: 130), "F47 exact independent expiry instants")
  }
  func content(_ snapshot: WorkoutActivitySnapshot, sequence: Int64) throws -> PowerLogRideAttributes.ContentState {
    WorkoutActivityProjection.content(try snapshot.project(sequence: sequence)!, at: snapshot.clock.utcUs)
  }
  check(
    !WorkoutActivityUpdateGate.deadlineReached(
      Date(timeIntervalSince1970: 121), at: Date(timeIntervalSince1970: 120.999999)),
    "Delivered deadline is upcoming before equality")
  check(
    WorkoutActivityUpdateGate.deadlineReached(Date(timeIntervalSince1970: 121), at: Date(timeIntervalSince1970: 121)),
    "Delivered expiry forces the latest presentation at equality")
  check(
    !WorkoutActivityUpdateGate.deadlineReached(nil, at: Date(timeIntervalSince1970: 121)),
    "Absent expiry never forces a deadline update")
  check(
    !WorkoutActivityUpdateGate.allows(deadline: false, controlChanged: false, lastUpdate: 105, now: 119.999),
    "Ordinary publications retain the 15-second throttle")
  check(
    WorkoutActivityUpdateGate.allows(deadline: false, controlChanged: false, lastUpdate: 105, now: 120),
    "Ordinary publications resume after 15 seconds")
  check(
    WorkoutActivityUpdateGate.allows(deadline: true, controlChanged: false, lastUpdate: 120, now: 121),
    "Bike deadline bypasses the 15-second throttle")
  check(
    WorkoutActivityUpdateGate.allows(deadline: true, controlChanged: false, lastUpdate: 129, now: 130),
    "HR deadline bypasses the 15-second throttle")
  check(
    WorkoutActivityUpdateGate.allows(deadline: false, controlChanged: true, lastUpdate: 120, now: 121),
    "Changed controls bypass throttle")
  for age in [Double.nan, .infinity, -1] {
    let invalid = WorkoutActivitySnapshot(
      rideID: ride, phase: "running", pendingAction: nil, timerSeconds: 10,
      observedAt: Date(timeIntervalSince1970: 105), observedUptime: 105,
      bikeSampleAgeSeconds: age, heartSampleAgeSeconds: age, riderPowerW: 200, heartRateBpm: 150, controlToken: token)
    let missing = try content(invalid, sequence: 1)
    check(
      missing.bikeExpiresAt == nil && missing.heartRateExpiresAt == nil,
      "Invalid native acquisition input has no deadline")
  }
  for shift in [-3600.0, 3600.0] {
    let mapped = WorkoutActivitySnapshot(
      rideID: ride, phase: "running", pendingAction: nil, timerSeconds: 10,
      observedAt: Date(timeIntervalSince1970: 105 + shift), observedUptime: 105,
      bikeSampleAgeSeconds: 5, heartSampleAgeSeconds: 5, riderPowerW: 200, heartRateBpm: 150, controlToken: token)
    let output = try content(mapped, sequence: 1)
    check(
      output.bikeExpiresAt == Date(timeIntervalSince1970: 121 + shift)
        && output.heartRateExpiresAt == Date(timeIntervalSince1970: 130 + shift),
      "App maps local monotonic acquisition into the explicit UTC clock")
  }
  let initial = try content(activitySnapshot(rideID: ride, token: token), sequence: 1)
  check(initial.timer.origin == Date(timeIntervalSince1970: 95), "Core supplies the running timer origin")
  var schedule = WorkoutActivitySchedule()
  schedule.replace(initial)
  let first = schedule.deadline!
  check(first.utcUs == 121_000_000 && schedule.take(first), "F47 schedules bike deadline at 121")
  let at121 = try content(activitySnapshot(rideID: ride, token: token, now: 121_000_000), sequence: 2)
  schedule.replace(at121)
  let second = schedule.deadline!
  check(second.utcUs == 130_000_000 && schedule.take(second), "F47 refresh at 121 schedules HR deadline at 130")
  let at130 = try content(activitySnapshot(rideID: ride, token: token, now: 130_000_000), sequence: 3)
  schedule.replace(at130)
  check(schedule.deadline == nil, "F47 has no future deadline after 130")
  let suspended = initial.render(at: Date(timeIntervalSince1970: 121), isStale: true)
  check(
    suspended.bike.text == "—" && suspended.heartRate.text == "—" && suspended.controls.isEmpty,
    "F47 suspension before 121 hides every live reading")
  check(
    at121.render(at: Date(timeIntervalSince1970: 129), isStale: false).heartRate.text == "150",
    "F47 HR remains after bike deadline update")
  check(
    at121.render(at: Date(timeIntervalSince1970: 130), isStale: true).heartRate.text == "—",
    "F47 suspension between deadlines hides HR at system stale")
  let missing = try content(activitySnapshot(rideID: ride, token: token, bike: nil), sequence: 4)
  check(
    missing.bikeExpiresAt == nil && missing.staleDate == Date(timeIntervalSince1970: 130),
    "Missing bike has no expiry; HR owns staleDate")
  check(
    missing.render(at: Date(timeIntervalSince1970: 105), isStale: false).bike.text == "—",
    "Missing acquisition renders unavailable")
  let foreign = try content(activitySnapshot(rideID: ride, token: token, epoch: "old-process"), sequence: 5)
  check(foreign.bikeExpiresAt == nil && foreign.heartRateExpiresAt == nil, "Foreign epoch never manufactures freshness")
  schedule.replace(initial)
  schedule.replace(try content(activitySnapshot(rideID: ride, token: token, bike: 104_000_000), sequence: 6))
  check(
    !schedule.take(first) && schedule.deadline?.utcUs == 125_000_000, "New acquisition rejects old scheduled callback")
  let refreshed = try content(activitySnapshot(rideID: ride, token: token, bike: 104_000_000), sequence: 8)
  schedule.replace(refreshed, delivered: initial, nowUtcUs: 105_000_000)
  check(
    schedule.deadline?.utcUs == 121_000_000 && schedule.deadline?.sequence == 8,
    "Throttled new reading refreshes at the delivered staleDate using current publication")
  schedule.replace(refreshed, delivered: initial, nowUtcUs: 121_000_000)
  check(schedule.deadline?.utcUs == 125_000_000, "Passed delivered expiry cannot cause a deadline loop")
  schedule.replace(try content(activitySnapshot(rideID: fixture["rideB"] as! String, token: token), sequence: 7))
  check(!schedule.take(first), "Changed ride rejects old deadline callback")
  schedule.replace(nil)
  check(schedule.deadline == nil, "Ending a ride cancels its native deadline")
}

func activityExternalFixtureChecks(_ check: (Bool, String) -> Void, _ rejects: (() throws -> Void, String) -> Void)
  throws
{
  let fixture = try activityFixture("actions")
  let ride = fixture["rideA"] as! String
  let token = fixture["token"] as! String
  for row in fixture["external"] as! [[String: Any]] {
    let actions = WorkoutActivityExternalActions()
    actions.adopt(try activitySnapshot(rideID: ride, token: token, pending: row["pending"] as? String))
    let request = try ExternalControlRequest(
      rideId: ride, controlToken: row["token"] as! String, expectedPhase: row["expectedPhase"] as! String,
      action: row["action"] as! String)
    let decision = try actions.decision(request, confirmed: row["confirmed"] as! Bool)
    check(
      decision.outcome == row["outcome"] as! String && decision.command == row["command"] as? String,
      "F35 production external admission \(row)")
    if decision.outcome == "rejected" {
      var effects = 0
      rejects(
        { try actions.perform(request, confirmed: false) { _, _ in effects += 1 } },
        "Rejected external controls have no effect")
      check(effects == 0 && actions.finish == nil, "Stale control cannot open confirmation or execute")
    }
  }
  let actions = WorkoutActivityExternalActions()
  let snapshot = try activitySnapshot(rideID: ride, token: token)
  let finish = try ExternalControlRequest(rideId: ride, controlToken: token, expectedPhase: "running", action: "finish")
  actions.adopt(snapshot)
  var commands: [String] = []
  rejects(
    { try actions.perform(finish, confirmed: true) { command, _ in commands.append(command) } },
    "Confirmation cannot be supplied without a retained Finish request")
  try actions.perform(finish, confirmed: false) { command, _ in commands.append(command) }
  check(commands.isEmpty && actions.finish != nil, "Finish opens shared sheet without invoking a command")
  check(
    (actions.state([:])["externalFinish"] as? [String: String]) == WorkoutActivityExternalActions.wire(finish),
    "Bridge supplies externalFinish to the shared screen")
  try actions.perform(finish, confirmed: false) { command, _ in commands.append(command) }
  check(
    commands.isEmpty
      && actions.finish.map(WorkoutActivityExternalActions.wire) == WorkoutActivityExternalActions.wire(finish),
    "Repeated Finish succeeds with the same pending confirmation and no command")
  actions.cancel(finish)
  check(actions.finish == nil && actions.controlToken != token, "Cancel clears the sheet and rotates the token")
  rejects({ try actions.perform(finish, confirmed: false) { _, _ in } }, "Cancelled controls cannot reopen Finish")
  for discard in [false, true] {
    let actions = WorkoutActivityExternalActions()
    actions.adopt(snapshot)
    try actions.perform(finish, confirmed: false) { _, _ in }
    try actions.perform(finish, confirmed: true, discard: discard) { command, nativeToken in
      commands.append(command)
      check(nativeToken == token, "Adapter invokes the existing owner entry point with its token")
    }
    check(commands.last == (discard ? "discard" : "stop"), "Shared Finish preserves Save and Discard")
    check(actions.finish == nil, "Confirmed Finish clears retained request")
    rejects({ try actions.perform(finish, confirmed: true) { _, _ in } }, "Duplicate confirmation cannot execute again")
  }
  for (rideID, phase, pending) in [
    (fixture["rideB"] as! String, "running", nil), (ride, "paused", nil), (ride, "running", "lap"),
    (ride, "failed", nil), (ride, "interrupted", nil),
  ] {
    let actions = WorkoutActivityExternalActions()
    actions.adopt(snapshot)
    try actions.perform(finish, confirmed: false) { _, _ in }
    actions.adopt(
      try activitySnapshot(rideID: rideID, token: UUID().uuidString.lowercased(), phase: phase, pending: pending))
    check(actions.finish == nil, "Ride, phase and pending changes invalidate Finish")
    rejects({ try actions.perform(finish, confirmed: true) { _, _ in } }, "Changed Finish rejects before owner command")
  }
}
