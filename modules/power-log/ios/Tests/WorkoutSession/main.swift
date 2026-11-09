import Foundation
import HealthKit

func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
  if !condition() {
    FileHandle.standardError.write(Data("WorkoutSession: \(message)\n".utf8))
    exit(1)
  }
}

final class FakeSession: WorkoutSessionSeam {
  var state: HKWorkoutSessionState = .notStarted
  var externalID: String?
  var calls: [String] = []
  weak var events: WorkoutSessionSeamEvents?
  var beginCollectionSucceeds = true
  var holdCollection: ((Bool, Error?) -> Void)?
  var holdsCollection = false
  var insertSucceeds = true
  var held: ((Bool, Error?) -> Void)?
  var hold = false
  var deliver: (([HealthOriginal]) -> Void)?
  var routeChunks: [Int] = []

  func addMetadata(_ workout: HealthPlanWorkout, completion: @escaping (Bool, Error?) -> Void) {
    calls.append("addMetadata")
    completion(true, nil)
  }
  func startActivity(at date: Date) {
    calls.append("startActivity")
    state = .running
  }
  func beginCollection(at date: Date, completion: @escaping (Bool, Error?) -> Void) {
    calls.append("beginCollection")
    if holdsCollection { return holdCollection = completion }
    completion(beginCollectionSucceeds, beginCollectionSucceeds ? nil : WorkoutSessionError.failed("collection"))
  }
  func startQueries(from date: Date, deliver: @escaping ([HealthOriginal]) -> Void) {
    calls.append("startQueries")
    self.deliver = deliver
  }
  func stopQueries() { calls.append("stopQueries") }
  func pause() { calls.append("pause") }
  func resume() { calls.append("resume") }
  func add(_ quantities: [HealthPlanQuantity], workout: HealthPlanWorkout, completion: @escaping (Bool, Error?) -> Void)
  {
    calls.append("add \(quantities.count)")
    if hold { held = completion } else { completion(true, nil) }
  }
  func addLap(at date: Date, completion: @escaping (Bool, Error?) -> Void) {
    calls.append("addLap")
    completion(true, nil)
  }
  func stopActivity(at date: Date) { calls.append("stopActivity") }
  func insertRoute(_ points: [RoutePoint], completion: @escaping (Bool, Error?) -> Void) {
    calls.append("insertRoute")
    routeChunks.append(points.count)
    completion(insertSucceeds, nil)
  }
  func endCollection(at date: Date, completion: @escaping (Bool, Error?) -> Void) {
    calls.append("endCollection")
    completion(true, nil)
  }
  func finishWorkout(completion: @escaping (String?, Error?) -> Void) {
    calls.append("finishWorkout")
    completion("ABCDEF00-0000-4000-8000-000000000001", nil)
  }
  func finishRoute(_ route: HealthPlanRoute, workout: HealthPlanWorkout, completion: @escaping (Bool, Error?) -> Void) {
    calls.append("finishRoute")
    completion(true, nil)
  }
  func discardWorkout() { calls.append("discardWorkout") }
  func end() {
    calls.append("end")
    state = .ended
  }
}

final class FakeStore: WorkoutStoreSeam {
  var available = true
  var sessions: [FakeSession] = []
  var next: () -> FakeSession = { FakeSession() }
  var orphan: FakeSession?
  var saved: String?
  var holdsRecovery = false
  var recovery: ((WorkoutSessionSeam?) -> Void)?

  func authorize(share: Bool, completion: @escaping (Bool, Error?) -> Void) { completion(true, nil) }
  func session(indoor: Bool, route: Bool, events: WorkoutSessionSeamEvents) throws -> WorkoutSessionSeam {
    let session = next()
    session.events = events
    sessions.append(session)
    return session
  }
  func recover(completion: @escaping (WorkoutSessionSeam?) -> Void) {
    if holdsRecovery { recovery = completion } else { completion(orphan) }
  }
  func savedWorkout(rideID: String, completion: @escaping (String?, Error?) -> Void) { completion(saved, nil) }
}

final class Rig {
  let queue = DispatchQueue(label: "health")
  let store = FakeStore()
  lazy var session = WorkoutSession(seam: store, queue: queue)
  let start = Date(timeIntervalSince1970: 1_788_679_800)
  lazy var workout = HealthPlanWorkout(
    rideID: "e8a00000-0000-4000-8000-000000000001", syncID: "powerlog:e8a00000-0000-4000-8000-000000000001:workout",
    syncVersion: 1, start: start)

  func run(_ block: @escaping () -> Void) {
    queue.sync(execute: block)
    settle()
  }

  func settle() { for _ in 0..<12 { queue.sync {} } }

  func begin() -> Error? {
    var answer: Error? = WorkoutSessionError.failed("unanswered")
    run { self.session.begin(self.workout, indoor: false, route: true) { answer = $0 } }
    return answer
  }

  func quantity(_ name: String, second: Int) -> HealthPlanQuantity {
    HealthPlanQuantity(
      syncID: "powerlog:\(workout.rideID):\(name):second:\(second)", quantity: name, value: 120,
      start: start.addingTimeInterval(Double(second)), end: start.addingTimeInterval(Double(second + 1)))
  }
}

func beginRunsTheOSSequenceAndASecondBeginIsBusy() {
  let rig = Rig()
  expect(rig.begin() == nil, "begin answers success")
  expect(
    rig.store.sessions[0].calls == ["addMetadata", "startActivity", "beginCollection", "startQueries"],
    "begin calls \(rig.store.sessions[0].calls)")
  expect((rig.begin() as? WorkoutSessionError) == .busy, "a second begin is busy")
}

func aFailedBeginDiscardsAndEndsAndLeavesNoSession() {
  let rig = Rig()
  rig.store.next = {
    let session = FakeSession()
    session.beginCollectionSucceeds = false
    return session
  }
  expect(rig.begin() != nil, "a failed collection fails begin")
  expect(rig.store.sessions[0].calls.suffix(2) == ["discardWorkout", "end"], "discarded and ended")
  rig.store.next = { FakeSession() }
  expect(rig.begin() == nil, "a later begin starts")
}

func addRejectsUnknownQuantitiesAndPassesPlansThrough() {
  let rig = Rig()
  _ = rig.begin()
  var unknown: Error?
  rig.run { rig.session.add([rig.quantity("heart-rate", second: 0)]) { unknown = $0 } }
  expect((unknown as? WorkoutSessionError) == .unknownQuantity("heart-rate"), "an unknown quantity is refused")
  var added: Error? = WorkoutSessionError.failed("unanswered")
  rig.run {
    rig.session.add([rig.quantity("rider-power", second: 0), rig.quantity("cycling-cadence", second: 0)]) { added = $0 }
  }
  expect(added == nil, "add answers success")
  expect(rig.store.sessions[0].calls.last == "add 2", "both quantities written in one call")
}

func finishStopsWaitsForStoppedInsertsTheRouteInChunksAndSavesTheWorkout() {
  let rig = Rig()
  _ = rig.begin()
  var states: [HKWorkoutSessionState] = []
  rig.session.onState = { state, _ in states.append(state) }
  let points = (0..<2_500).map {
    RoutePoint(
      at: rig.start.addingTimeInterval(Double($0)), latitude: 46.5, longitude: 11.35, altitudeM: 480,
      horizontalAccuracyM: 3, verticalAccuracyM: 4, speedMps: 6, courseDeg: 90)
  }
  var result: Result<(String, Bool), Error>?
  rig.run {
    rig.session.finish(at: rig.start.addingTimeInterval(4_356), route: HealthPlanRoute(syncID: "route", points: points))
    {
      result = $0
    }
  }
  let fake = rig.store.sessions[0]
  expect(fake.calls.suffix(2) == ["stopQueries", "stopActivity"], "finish stops first: \(fake.calls)")
  expect(result == nil, "finish waits for the stopped state")
  fake.state = .stopped
  fake.events?.stateChanged(.stopped, at: Date())
  rig.settle()
  expect(fake.routeChunks == [1_000, 1_000, 500], "route chunks \(fake.routeChunks)")
  expect(
    Array(fake.calls.suffix(4)) == ["endCollection", "finishWorkout", "finishRoute", "end"],
    "finish sequence \(fake.calls)")
  guard case .success(let (id, routeSaved)) = result else { return expect(false, "finish answers success") }
  expect(id == "abcdef00-0000-4000-8000-000000000001" && routeSaved, "the lowercase id and the saved route")
  expect(states == [.stopped], "the state reached the caller")
  expect(rig.begin() == nil, "a finished session frees the slot")
}

func aRouteThatFailsToInsertStillSavesTheWorkoutWithoutIt() {
  let rig = Rig()
  rig.store.next = {
    let session = FakeSession()
    session.insertSucceeds = false
    session.state = .stopped
    return session
  }
  _ = rig.begin()
  rig.store.sessions[0].state = .stopped
  var result: Result<(String, Bool), Error>?
  let route = HealthPlanRoute(
    syncID: "route",
    points: [
      RoutePoint(
        at: rig.start, latitude: 46.5, longitude: 11.35, altitudeM: nil, horizontalAccuracyM: 3, verticalAccuracyM: nil,
        speedMps: nil, courseDeg: nil)
    ])
  rig.run { rig.session.finish(at: rig.start, route: route) { result = $0 } }
  expect(!rig.store.sessions[0].calls.contains("finishRoute"), "no route finished")
  guard case .success(let (_, routeSaved)) = result else { return expect(false, "finish answers success") }
  expect(!routeSaved, "the route is reported unsaved")
}

func discardEndsTheSessionAndDropsALateCompletion() {
  let rig = Rig()
  _ = rig.begin()
  let fake = rig.store.sessions[0]
  fake.hold = true
  var answered = false
  rig.run { rig.session.add([rig.quantity("rider-power", second: 3)]) { _ in answered = true } }
  rig.run { rig.session.discard(at: rig.start.addingTimeInterval(30)) }
  expect(
    Array(fake.calls.suffix(4)) == ["stopQueries", "stopActivity", "discardWorkout", "end"],
    "discard sequence \(fake.calls)")
  fake.held?(true, nil)
  rig.settle()
  expect(!answered, "a completion after discard is dropped")
}

func sessionEventsAndCollectedSamplesReachTheCaller() {
  let rig = Rig()
  var rates: [LiveHeartRate] = []
  var collected: [HealthOriginal] = []
  rig.session.onHeartRateReceived = { rates.append($0) }
  rig.session.onSamplesCollected = { collected += $0 }
  _ = rig.begin()
  let fake = rig.store.sessions[0]
  fake.events?.heartRate(LiveHeartRate(bpm: 131, at: rig.start))
  fake.deliver?([
    HealthOriginal(
      sampleID: "s", member: nil, metric: "heartRateBpm", value: 131, start: rig.start, end: rig.start,
      dataOrigin: "com.apple.health", syncID: nil)
  ])
  rig.settle()
  expect(rates.map(\.bpm) == [131], "heart rate delivered")
  expect(collected.map(\.sampleID) == ["s"], "samples delivered")
}

func recoverOrphanEndsTheRecoveredSessionAndNamesItsRide() {
  let rig = Rig()
  let orphan = FakeSession()
  orphan.externalID = "e8a00000-0000-4000-8000-000000000002"
  rig.store.orphan = orphan
  var named: String?
  rig.run { rig.session.recoverOrphan { named = $0 } }
  expect(named == "e8a00000-0000-4000-8000-000000000002", "the orphan's ride")
  expect(orphan.calls == ["stopActivity", "discardWorkout", "end"], "the orphan is ended, never saved")
  rig.store.saved = "ABC"
  var saved: Result<String?, Error>?
  rig.run { rig.session.savedWorkout(rideID: "r") { saved = $0 } }
  guard case .success(let id) = saved else { return expect(false, "savedWorkout answers") }
  expect(id == "abc", "a saved workout's lowercase id")
}

func aSessionTheOSEndsOrFailsFreesTheSlot() {
  let rig = Rig()
  var states: [HKWorkoutSessionState] = []
  var failures = 0
  rig.session.onState = { state, _ in states.append(state) }
  rig.session.onFailure = { _ in failures += 1 }
  _ = rig.begin()
  let ended = rig.store.sessions[0]
  ended.events?.stateChanged(.ended, at: Date())
  rig.settle()
  expect(states == [.ended], "the end reached the caller")
  expect(Array(ended.calls.suffix(3)) == ["stopQueries", "discardWorkout", "end"], "released: \(ended.calls)")
  expect(rig.begin() == nil, "a begin after the OS ended the session starts")
  let failed = rig.store.sessions[1]
  failed.events?.failed(WorkoutSessionError.failed("os"))
  rig.settle()
  expect(failures == 1, "the failure reached the caller")
  expect(Array(failed.calls.suffix(3)) == ["stopQueries", "discardWorkout", "end"], "released: \(failed.calls)")
  expect(rig.begin() == nil, "a begin after a failed session starts")
}

func theOSEndingAFinishKeepsTheFinishGoing() {
  let rig = Rig()
  _ = rig.begin()
  var result: Result<(String, Bool), Error>?
  rig.run { rig.session.finish(at: rig.start.addingTimeInterval(60), route: nil) { result = $0 } }
  let fake = rig.store.sessions[0]
  fake.state = .ended
  fake.events?.stateChanged(.ended, at: Date())
  rig.settle()
  expect(!fake.calls.contains("discardWorkout"), "a finishing session is not discarded")
  guard case .success = result else { return expect(false, "finish answers success") }
}

func aBeginWhileAnOrphanIsRecoveredIsBusy() {
  let rig = Rig()
  rig.store.holdsRecovery = true
  var named: String?? = .none
  rig.run { rig.session.recoverOrphan { named = .some($0) } }
  expect((rig.begin() as? WorkoutSessionError) == .busy, "begin waits out the orphan recovery")
  rig.store.recovery?(nil)
  rig.settle()
  expect(named == .some(nil), "no orphan")
  expect(rig.begin() == nil, "begin starts after the recovery")
}

func aDiscardDuringBeginAnswersBeginOnce() {
  let rig = Rig()
  rig.store.next = {
    let session = FakeSession()
    session.holdsCollection = true
    return session
  }
  var answers: [Error?] = []
  rig.run { rig.session.begin(rig.workout, indoor: false, route: false) { answers.append($0) } }
  expect(answers.isEmpty, "begin waits for the collection")
  rig.run { rig.session.discard(at: rig.start) }
  rig.store.sessions[0].holdCollection?(true, nil)
  rig.settle()
  expect(answers.count == 1 && (answers[0] as? WorkoutSessionError) == .noSession, "begin answered once: \(answers)")
}

func theHeartRateStampIsTakenInTheCallback() {
  let rig = Rig()
  var stamps = 0
  var delivered: [(Double, Int)] = []
  rig.session.heartRateStamp = {
    stamps += 1
    return NSNumber(value: stamps)
  }
  rig.session.onStampedHeartRate = { rate, stamp in delivered.append((rate.bpm, (stamp as? NSNumber)?.intValue ?? 0)) }
  _ = rig.begin()
  rig.store.sessions[0].events?.heartRate(LiveHeartRate(bpm: 140, at: rig.start))
  expect(stamps == 1 && delivered.isEmpty, "stamped before the hop")
  rig.settle()
  expect(delivered.count == 1 && delivered[0] == (140, 1), "the stamp travels with the rate")
}

final class FakeSeries: HealthSeriesQueries {
  var members: [UUID: [Double]] = [:]
  var failing: Set<UUID> = []
  var asked: [UUID] = []

  func members(
    of sample: HealthQuerySample, metric: AppleHealthTypes.Metric, member: @escaping (Double, Date, Date) -> Void,
    done: @escaping (Error?) -> Void
  ) -> AnyObject? {
    asked.append(sample.id)
    if failing.contains(sample.id) {
      member(1, sample.start, sample.start)
      done(HKError(.errorDatabaseInaccessible))
      return nil
    }
    for (offset, value) in (members[sample.id] ?? []).enumerated() {
      let start = sample.start.addingTimeInterval(Double(offset))
      member(value, start, start)
    }
    done(nil)
    return nil
  }

  func stop(_ query: AnyObject) {}
}

func theExpanderGivesSeriesMembersAndFailsAPageWhoseSeriesFails() {
  let queue = DispatchQueue(label: "expander")
  let series = FakeSeries()
  let expander = HealthSeriesExpander(queries: series, queue: queue)
  let metric = AppleHealthTypes.readback[0]
  let start = Date(timeIntervalSince1970: 1_788_679_800)
  func sample(_ count: Int, _ value: Double = 120) -> HealthQuerySample {
    HealthQuerySample(
      id: UUID(), count: count, value: value, start: start, end: start.addingTimeInterval(1), origin: "watch",
      syncID: nil)
  }
  let single = sample(1, 99)
  let many = sample(3)
  series.members[many.id] = [120, .nan, 122]
  var result: Result<[HealthOriginal], Error>?
  expander.expand([single, many], metric: metric) { result = $0 }
  for _ in 0..<12 { queue.sync {} }
  guard case .success(let originals) = result else { return expect(false, "the page expands") }
  expect(originals.map(\.member) == [nil, 0, 2], "members keep their index: \(originals.map(\.member))")
  expect(originals.map(\.value) == [99, 120, 122], "non-finite values are skipped")
  expect(originals[1].sampleID == many.id.uuidString.lowercased(), "lowercase sample ids")
  expect(series.asked == [many.id], "only a series is queried")

  let broken = sample(2)
  series.failing = [broken.id]
  result = nil
  expander.expand([single, broken], metric: metric) { result = $0 }
  for _ in 0..<12 { queue.sync {} }
  guard case .failure(let error) = result else { return expect(false, "a failed series fails the page") }
  expect((error as? HKError)?.code == .errorDatabaseInaccessible, "the series error is the page's")
}

func healthTypesCodeAsTheKotlinKeysInMicroseconds() {
  let quantity = HealthPlanQuantity(
    syncID: "powerlog:r:rider-power:second:3", quantity: "rider-power", value: 181.5,
    start: Date(timeIntervalSince1970: 1_788_679_803), end: Date(timeIntervalSince1970: 1_788_679_804))
  let encoder = JSONEncoder()
  encoder.outputFormatting = .sortedKeys
  let text = String(decoding: try! encoder.encode(quantity), as: UTF8.self)
  expect(
    text
      == #"{"endUtcUs":1788679804000000,"quantity":"rider-power","startUtcUs":1788679803000000,"syncId":"powerlog:r:rider-power:second:3","value":181.5}"#,
    "quantity JSON \(text)")
  expect(try! JSONDecoder().decode(HealthPlanQuantity.self, from: Data(text.utf8)) == quantity, "quantity round trip")
  let original = HealthOriginal(
    sampleID: "a", member: nil, metric: "heartRateBpm", value: 120, start: Date(timeIntervalSince1970: 1),
    end: Date(timeIntervalSince1970: 2), dataOrigin: "o", syncID: nil)
  let decoded = try! JSONDecoder().decode(HealthOriginal.self, from: try! encoder.encode(original))
  expect(decoded == original, "original round trip")
  let point = RoutePoint(
    at: Date(timeIntervalSince1970: 3), latitude: 1, longitude: 2, altitudeM: nil, horizontalAccuracyM: 4,
    verticalAccuracyM: nil, speedMps: 5, courseDeg: nil)
  expect(
    try! JSONDecoder().decode(RoutePoint.self, from: try! encoder.encode(point)) == point, "route point round trip")
  let rate = LiveHeartRate(bpm: 99, at: Date(timeIntervalSince1970: 4))
  expect(
    String(decoding: try! encoder.encode(rate), as: UTF8.self) == #"{"bpm":99,"utcUs":4000000}"#, "heart rate JSON")
}

#if os(iOS)
  func healthKitSamplesBecomeQuerySamples() {
    let start = Date(timeIntervalSince1970: 1_788_679_800)
    let sample = HKQuantitySample(
      type: HKQuantityType(.heartRate),
      quantity: HKQuantity(unit: .count().unitDivided(by: .minute()), doubleValue: 131),
      start: start, end: start.addingTimeInterval(1),
      metadata: [HKMetadataKeySyncIdentifier: "powerlog:r:x", HKMetadataKeySyncVersion: 1])
    let query = HealthQuerySample(sample, metric: AppleHealthTypes.readback[0])
    expect(query.id == sample.uuid && query.count == 1, "the sample's id and count")
    expect(query.value == 131 && query.syncID == "powerlog:r:x", "the value in the metric's unit and the sync id")
    let store = HKHealthStore()
    _ = WorkoutSession(store: store, queue: DispatchQueue(label: "simulator"))
    _ = HealthSeriesExpander(queries: HealthKitSeriesQueries(store: store), queue: DispatchQueue(label: "series"))
  }
  healthKitSamplesBecomeQuerySamples()
#endif
beginRunsTheOSSequenceAndASecondBeginIsBusy()
aFailedBeginDiscardsAndEndsAndLeavesNoSession()
addRejectsUnknownQuantitiesAndPassesPlansThrough()
finishStopsWaitsForStoppedInsertsTheRouteInChunksAndSavesTheWorkout()
aRouteThatFailsToInsertStillSavesTheWorkoutWithoutIt()
discardEndsTheSessionAndDropsALateCompletion()
sessionEventsAndCollectedSamplesReachTheCaller()
recoverOrphanEndsTheRecoveredSessionAndNamesItsRide()
healthTypesCodeAsTheKotlinKeysInMicroseconds()
aSessionTheOSEndsOrFailsFreesTheSlot()
theOSEndingAFinishKeepsTheFinishGoing()
aBeginWhileAnOrphanIsRecoveredIsBusy()
aDiscardDuringBeginAnswersBeginOnce()
theHeartRateStampIsTakenInTheCallback()
theExpanderGivesSeriesMembersAndFailsAPageWhoseSeriesFails()
print("WorkoutSession: 15 cases passed")
