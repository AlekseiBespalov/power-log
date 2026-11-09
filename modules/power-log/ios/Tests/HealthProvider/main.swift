import Foundation
import HealthKit
import PowerLogCore

func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
  if !condition() {
    FileHandle.standardError.write(Data("AppleHealthProvider: \(message)\n".utf8))
    exit(1)
  }
}

final class StepClock: NSObject, ApplicationClock {
  func monoUs() -> Int64 { 5 }
  func utcUs() -> Int64 { 1_788_679_800_000_000 }
  func read() -> DomainClockReading { DomainClockReading(monoUs: monoUs(), utcUs: utcUs()) }
}

final class FakeHealthStore: AppleHealthStore {
  var available = true
  var statuses: [String: HKAuthorizationStatus] = [:]
  var authorizations: [Bool] = []
  var authorizeError: Error?
  var reads: [(String, Date, Date, Data?)] = []
  var page: Result<AppleHealthAnchoredPage, Error> = .success(AppleHealthAnchoredPage(samples: [], anchor: nil))
  let fakeSeries = Members()
  var series: HealthSeriesQueries { fakeSeries }

  func writeStatus(_ type: HKObjectType) -> HKAuthorizationStatus { statuses[type.identifier] ?? .notDetermined }
  func authorize(share: Bool, completion: @escaping (Error?) -> Void) {
    authorizations.append(share)
    completion(authorizeError)
  }
  func anchored(
    _ metric: AppleHealthTypes.Metric, start: Date, end: Date, anchor: Data?, limit: Int,
    completion: @escaping (Result<AppleHealthAnchoredPage, Error>) -> Void
  ) {
    reads.append((metric.key, start, end, anchor))
    completion(page)
  }
}

final class Members: HealthSeriesQueries {
  var values: [Double] = []
  var error: Error?

  func members(
    of sample: HealthQuerySample, metric: AppleHealthTypes.Metric, member: @escaping (Double, Date, Date) -> Void,
    done: @escaping (Error?) -> Void
  ) -> AnyObject? {
    for (offset, value) in values.enumerated() {
      member(value, sample.start.addingTimeInterval(Double(offset)), sample.start.addingTimeInterval(Double(offset)))
    }
    done(error)
    return nil
  }

  func stop(_ query: AnyObject) {}
}

final class NoSessions: WorkoutStoreSeam {
  var available = true
  func authorize(share: Bool, completion: @escaping (Bool, Error?) -> Void) { completion(true, nil) }
  func session(indoor: Bool, route: Bool, events: WorkoutSessionSeamEvents) throws -> WorkoutSessionSeam {
    throw WorkoutSessionError.unavailable
  }
  func recover(completion: @escaping (WorkoutSessionSeam?) -> Void) { completion(nil) }
  func savedWorkout(rideID: String, completion: @escaping (String?, Error?) -> Void) { completion("ABC", nil) }
}

final class Answer<T: AnyObject> {
  var value: T?
  var error: ApplicationPortError?
  var settled = false
  lazy var reply = ApplicationReply<T>(
    onOk: { [unowned self] in
      value = $0
      settled = true
    },
    onFailed: { [unowned self] in
      error = $0
      settled = true
    })
}

let queue = DispatchQueue(label: "health")
func settle() { for _ in 0..<8 { queue.sync {} } }
func settle<T>(_ answer: Answer<T>) {
  for _ in 0..<10_000 where !answer.settled { queue.sync {} }
}

func grantsShowWriteStatusPerComponentAndHideReads() {
  let store = FakeHealthStore()
  store.statuses[HKObjectType.workoutType().identifier] = .sharingAuthorized
  store.statuses[HKQuantityType.quantityType(forIdentifier: .cyclingPower)!.identifier] = .sharingDenied
  let provider = AppleHealthProvider(
    store: store, session: WorkoutSession(seam: NoSessions(), queue: queue), queue: queue, clock: StepClock())
  let answer = Answer<ApplicationHealthGrants>()
  provider.access.grants(reply: answer.reply)
  expect(!answer.settled, "grants answer after the call returns")
  settle()
  let grants = answer.value!
  expect(grants.write[.workout] == .granted, "workout granted")
  expect(grants.write[.riderpower] == .denied, "power denied")
  expect(grants.write[.cyclingcadence] == .notdetermined, "cadence not determined")
  expect(grants.write[.readback] == nil, "no write state for readback")
  expect(grants.read["heartRateBpm"] == .hidden, "reads are hidden")
  expect(grants.backgroundRead == nil && grants.readableFromUtcUs == nil, "no background read or window")
  expect(provider.access.availability() == .available, "available")
  store.available = false
  expect(provider.access.availability() == .unsupported, "unavailable without Health data")
}

func requestAsksShareOnlyWhenThePhoneWritesAndMapsADenial() {
  let store = FakeHealthStore()
  let provider = AppleHealthProvider(
    store: store, session: WorkoutSession(seam: NoSessions(), queue: queue), queue: queue, clock: StepClock())
  let reading = Answer<KotlinUnit>()
  provider.access.request(
    scope: ApplicationHealthScope(write: [], read: ["heartRateBpm"], route: false, backgroundRead: false),
    reply: reading.reply)
  let writing = Answer<KotlinUnit>()
  provider.access.request(
    scope: ApplicationHealthScope(write: [.workout], read: [], route: true, backgroundRead: false), reply: writing.reply
  )
  expect(store.authorizations == [false, true], "share asked only when writing: \(store.authorizations)")
  expect(reading.value != nil && writing.value != nil, "a request answers once the sheet closes")
  store.authorizeError = HKError(.errorAuthorizationDenied)
  let denied = Answer<KotlinUnit>()
  provider.access.request(
    scope: ApplicationHealthScope(write: [.workout], read: [], route: false, backgroundRead: false), reply: denied.reply
  )
  expect(denied.error?.kind == .denied, "a denial maps to denied")
  expect(AppleHealthProvider.portError(HKError(.errorDatabaseInaccessible)).kind == .locked, "locked data")
  expect(AppleHealthProvider.portError(HKError(.errorHealthDataUnavailable)).kind == .unsupported, "no Health")
  expect(AppleHealthProvider.portError(WorkoutSessionError.busy).kind == .busy, "a second begin is busy")
}

func readPagesWithTheAnchorAsTheCursorAndEndsOnAShortPage() {
  let store = FakeHealthStore()
  let provider = AppleHealthProvider(
    store: store, session: WorkoutSession(seam: NoSessions(), queue: queue), queue: queue, clock: StepClock())
  let start = Date(timeIntervalSince1970: 1_788_679_800)
  let series = HealthQuerySample(
    id: UUID(uuidString: "A1000000-0000-4000-8000-000000000001")!, count: 2, value: 0, start: start,
    end: start.addingTimeInterval(2), origin: "com.apple.health", syncID: nil)
  store.fakeSeries.values = [131, 132]
  store.page = .success(
    AppleHealthAnchoredPage(samples: Array(repeating: series, count: 256), anchor: Data([1, 2, 3])))
  let full = Answer<ApplicationHealthOriginalPage>()
  provider.reader.read(
    metric: "heartRateBpm", startUtcUs: 1_788_679_800_000_000, endUtcUs: 1_788_683_400_000_000, cursor: nil,
    reply: full.reply)
  settle(full)
  expect(full.value?.next == Data([1, 2, 3]).base64EncodedString(), "a full page names the next anchor")
  let sample = full.value!.samples[1]
  expect(sample.sampleId == "a1000000-0000-4000-8000-000000000001" && sample.member?.intValue == 1, "series members")
  expect(sample.startUtcUs == 1_788_679_801_000_000, "instants in µs")
  expect(full.value!.samples.count == 512, "every member of every sample")
  store.page = .success(AppleHealthAnchoredPage(samples: [], anchor: Data([4])))
  let last = Answer<ApplicationHealthOriginalPage>()
  provider.reader.read(
    metric: "heartRateBpm", startUtcUs: 0, endUtcUs: 1, cursor: full.value?.next, reply: last.reply)
  settle(last)
  expect(last.value != nil && last.value?.next == nil, "a short page ends the read")
  expect(store.reads.last?.3 == Data([1, 2, 3]), "the cursor is the anchor")
  let unknown = Answer<ApplicationHealthOriginalPage>()
  provider.reader.read(metric: "stepCount", startUtcUs: 0, endUtcUs: 1, cursor: nil, reply: unknown.reply)
  expect(!unknown.settled, "an unknown metric answers after the call returns")
  settle()
  expect(unknown.error?.kind == .invalid, "an unknown metric is invalid")
}

func aLargeBatchOfSingleSamplesExpandsWithoutDeepRecursion() {
  let start = Date(timeIntervalSince1970: 1_788_679_800)
  let single = HealthQuerySample(
    id: UUID(uuidString: "B1000000-0000-4000-8000-000000000001")!, count: 1, value: 131, start: start, end: start,
    origin: "com.apple.health", syncID: nil)
  let expander = HealthSeriesExpander(queries: Members(), queue: queue)
  let settled = DispatchSemaphore(value: 0)
  var expanded = 0
  expander.expand(Array(repeating: single, count: 200_000), metric: AppleHealthTypes.readback[0]) { result in
    if case .success(let originals) = result { expanded = originals.count }
    settled.signal()
  }
  expect(settled.wait(timeout: .now() + 60) == .success, "a large batch of single samples settles")
  expect(expanded == 200_000, "every single sample is expanded: \(expanded)")
}

func aFailedSeriesOrABadCursorFailsTheRead() {
  let store = FakeHealthStore()
  let provider = AppleHealthProvider(
    store: store, session: WorkoutSession(seam: NoSessions(), queue: queue), queue: queue, clock: StepClock())
  let start = Date(timeIntervalSince1970: 1_788_679_800)
  store.page = .success(
    AppleHealthAnchoredPage(
      samples: [
        HealthQuerySample(
          id: UUID(), count: 3, value: 0, start: start, end: start, origin: "com.apple.health", syncID: nil)
      ], anchor: Data([9])))
  store.fakeSeries.values = [120]
  store.fakeSeries.error = HKError(.errorDatabaseInaccessible)
  let locked = Answer<ApplicationHealthOriginalPage>()
  provider.reader.read(metric: "heartRateBpm", startUtcUs: 0, endUtcUs: 1, cursor: nil, reply: locked.reply)
  settle(locked)
  expect(locked.error?.kind == .locked, "a series that fails fails its page: \(String(describing: locked.error))")
  let garbled = Answer<ApplicationHealthOriginalPage>()
  provider.reader.read(metric: "heartRateBpm", startUtcUs: 0, endUtcUs: 1, cursor: "%%%", reply: garbled.reply)
  settle(garbled)
  expect(garbled.error?.kind == .invalid, "a cursor that isn't base64 is invalid")
  expect(store.reads.count == 1, "no query for a bad cursor")
  store.page = .failure(AppleHealthReadError.cursor)
  let undecodable = Answer<ApplicationHealthOriginalPage>()
  provider.reader.read(
    metric: "heartRateBpm", startUtcUs: 0, endUtcUs: 1, cursor: Data([7]).base64EncodedString(),
    reply: undecodable.reply)
  settle(undecodable)
  expect(undecodable.error?.kind == .invalid, "an anchor that doesn't decode is invalid")
}

#if POWER_LOG_DEV_TOOLS
  func theReadbackKeysAreTheDomains() {
    let kotlin = SuiteTables.shared.healthMetrics.readback(provider: .applehealth).map {
      String(describing: $0).replacingOccurrences(of: "HealthMetric(wire=", with: "").replacingOccurrences(
        of: ")", with: "")
    }
    expect(AppleHealthTypes.readback.map(\.key) == kotlin, "Swift \(AppleHealthTypes.readback.map(\.key)) vs \(kotlin)")
  }
#endif

func theSessionAdapterRunsTheSharedSessionOnItsQueue() {
  let store = FakeHealthStore()
  let session = WorkoutSession(seam: NoSessions(), queue: queue)
  let provider = AppleHealthProvider(store: store, session: session, queue: queue, clock: StepClock())
  let begin = Answer<KotlinUnit>()
  let workout = DomainHealthPlanWorkout(
    syncId: "powerlog:r:workout", rideId: "r", startUtcUs: 1_788_679_800_000_000, endUtcUs: nil, indoor: false,
    laps: [], pauses: [])
  provider.session!.begin(
    workout: workout, options: ApplicationSessionOptions(indoor: false, route: true), reply: begin.reply)
  settle()
  expect(begin.error?.kind == .unsupported, "a store without sessions answers unsupported")
  let saved = Answer<NSString>()
  provider.session!.savedWorkout(rideId: "r", reply: saved.reply)
  settle()
  expect(saved.value == "abc", "a saved workout's lowercase id")
  expect(AppleWorkoutSessionAdapter.state(.paused) == .paused, "states map")
  expect(AppleTime.serialName(.riderpower) == "rider-power", "quantity serial names")
}

#if os(iOS)
  func healthKitsStoreRefusesAnUndecodableAnchorAndAnswersAQuery() {
    let store = HealthKitHealthStore(store: HKHealthStore())
    let metric = AppleHealthTypes.readback[0]
    var undecodable: Result<AppleHealthAnchoredPage, Error>?
    store.anchored(metric, start: Date(timeIntervalSince1970: 0), end: Date(), anchor: Data([1, 2]), limit: 1) {
      undecodable = $0
    }
    guard case .failure(let error) = undecodable else { return expect(false, "an undecodable anchor fails") }
    expect(AppleHealthProvider.portError(error).kind == .invalid, "an undecodable anchor is invalid")
    let answered = DispatchSemaphore(value: 0)
    store.anchored(metric, start: Date(timeIntervalSince1970: 0), end: Date(), anchor: nil, limit: 1) { _ in
      answered.signal()
    }
    expect(answered.wait(timeout: .now() + 20) == .success, "HealthKit answers an anchored query")
  }
  healthKitsStoreRefusesAnUndecodableAnchorAndAnswersAQuery()
#endif
grantsShowWriteStatusPerComponentAndHideReads()
requestAsksShareOnlyWhenThePhoneWritesAndMapsADenial()
readPagesWithTheAnchorAsTheCursorAndEndsOnAShortPage()
aFailedSeriesOrABadCursorFailsTheRead()
aLargeBatchOfSingleSamplesExpandsWithoutDeepRecursion()
#if POWER_LOG_DEV_TOOLS
  theReadbackKeysAreTheDomains()
#endif
theSessionAdapterRunsTheSharedSessionOnItsQueue()
print("AppleHealthProvider: 7 cases passed")
