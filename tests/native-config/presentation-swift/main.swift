import Foundation
import PowerLogCore

var checks = 0
func check(_ value: Bool, file: StaticString = #file, line: UInt = #line) {
  precondition(value, file: file, line: line)
  checks += 1
}
func rejects(_ work: () throws -> Void) {
  do {
    try work()
    fatalError("Invalid presentation input was admitted")
  } catch {
    check((error as NSError).domain == "KotlinException")
  }
}

func startContext(
  surface: StartSurface = .rider, hasSession: Bool = false, recovering: Bool = false, unresolvedOwner: Bool = false
) -> RideStartContext {
  RideStartContext(
    surface: surface, hasSession: hasSession, endingOwner: false, recoveredForeignOwner: false,
    unresolvedOwner: unresolvedOwner, finishing: false, busy: false, recovering: recovering,
    deletionProbeInFlight: false)
}

let ride = "11111111-1111-4111-8111-111111111111"
let token = "66666666-6666-4666-8666-666666666666"
let acquisition = try LiveAcquisition(
  observationId: "observation-a", sequence: KotlinLong(value: 1), originalTimeUs: 100_000_000,
  epoch: "process-a", monotonicUs: 100_000_000)
let reading = try LiveReading(acquisition: acquisition, values: ["humanPowerW": KotlinDouble(value: 200)])
let acquired = try PresentationClock(epoch: "process-a", monotonicUs: 100_000_000, utcUs: 100_000_000)
var state = try LivePresentation.shared.adopt(state: LivePresentation.shared.empty(), rideId: ride)
state = try LivePresentation.shared.observe(
  state: state, rideId: ride, source: .controller, reading: reading, clock: acquired, backfilled: false)
let now = try PresentationClock(epoch: "process-a", monotonicUs: 105_000_000, utcUs: 105_000_000)
let input = try ActivityInput(
  rideId: ride, publicationSequence: 1, controlToken: token, phase: "running", pendingAction: nil,
  timerSeconds: KotlinDouble(value: 10))
let activity = ActivityPresentations.shared.project(input: input, state: state, clock: now)
check(activity.bikeExpiresAtUs?.int64Value == 106_000_000)
check(activity.staleDateUs?.int64Value == 106_000_000)
check(activity.render(nowUtcUs: 105_999_999, systemStale: false).bike.text == "200")
check(activity.render(nowUtcUs: 106_000_000, systemStale: false).bike.text == "—")
check(activity.render(nowUtcUs: 106_000_000, systemStale: true).controls.isEmpty)
check(activity.timer.originUs?.int64Value == 95_000_000)

let capabilities = RideCapabilities(
  phoneWorkout: true, watchWorkout: true, phoneHealth: true, watchHealth: true, gps: true,
  healthProvider: "appleHealth", foregroundOnly: false)
let requested = RequestedRideOptions(
  indoor: false, useWatch: nil, saveToHealth: KotlinBoolean(value: false), recordGPS: nil,
  sampleHz: KotlinDouble(value: 8))
let frozen = try RideOptions.shared.resolve(requested: requested, capabilities: capabilities)
check(frozen.sampleHz == 8 && frozen.recordGPS && !frozen.saveToHealth)
check(
  RideOptions.shared.readiness(
    options: frozen, capabilities: capabilities, bikeReady: false, watchInstalled: false,
    phase: nil, startContext: startContext(), error: nil
  ).error == "bikeRequired")
check(
  RideOptions.shared.readiness(
    options: frozen, capabilities: capabilities, bikeReady: true, watchInstalled: false,
    phase: nil, startContext: startContext(), error: nil
  ).available)
rejects {
  _ = try RideOptions.shared.restore(
    indoor: false, useWatch: false, saveToHealth: false, recordGPS: true, sampleHz: nil)
}
rejects {
  _ = try SetupPresentation.shared.health(
    requested: RequestedRideOptions(
      indoor: false, useWatch: nil, saveToHealth: nil, recordGPS: nil, sampleHz: KotlinDouble(value: 3)),
    capabilities: capabilities)
}
rejects {
  _ = try LiveReading(acquisition: acquisition, values: ["unknown": KotlinDouble(value: 1)])
}
let defaults = Preferences.shared.defaults()
let decoded = try Preferences.shared.decode(text: Preferences.shared.encode(preferences: defaults))
check(decoded.sampleHz == 2 && decoded.activeView == .ride)
let unreadable = Preferences.shared.load(previous: decoded, text: "bad JSON", loadError: nil)
check(!unreadable.shouldSave && !unreadable.writable && unreadable.error != nil)
check(!Preferences.shared.change(state: unreadable, replacement: defaults).shouldSave)
let wait = try PresentationTimeouts.shared.evaluate(kind: .recovery, beganAtUs: 0, nowUs: 15_000_000)
check(wait.outcome == "unresolved" && !wait.pending)
let context = try ExternalControlContext(rideId: ride, controlToken: token, phase: "running", pendingAction: nil)
let finish = try ExternalControlRequest(rideId: ride, controlToken: token, expectedPhase: "running", action: "finish")
check(RideActions.shared.external(current: context, request: finish, confirmed: false).command == nil)
check(RideActions.shared.external(current: context, request: finish, confirmed: true).command == "stop")
check(
  try MetricCatalog.shared.format(id: "distanceMeters", value: KotlinDouble(value: 1234.56), speedUnit: "mph") == "1.23"
)
check(
  try MetricCatalog.shared.formatExact(id: "humanPowerW", original: "9007199254740993", speedUnit: "km/h")
    == "9007199254740993")
let localAcquisition = try LiveAcquisition(
  observationId: "local-heart", sequence: nil, originalTimeUs: 100_000_000,
  epoch: "process-a", monotonicUs: 100_000_000)
check(localAcquisition.sequence == nil)
state = try LivePresentation.shared.observe(
  state: state, rideId: ride, source: .heartrate,
  reading: LiveReading(acquisition: localAcquisition, values: ["heartRateBpm": KotlinDouble(value: 150)]),
  clock: acquired, backfilled: false)
let replay = try LiveReading(
  acquisition: LiveAcquisition(
    observationId: "observation-a", sequence: KotlinLong(value: 2), originalTimeUs: 110_000_000, epoch: "process-a",
    monotonicUs: 110_000_000),
  values: ["humanPowerW": KotlinDouble(value: 300)])
let replayed = try LivePresentation.shared.observe(
  state: state, rideId: ride, source: .controller, reading: replay,
  clock: PresentationClock(epoch: "process-a", monotonicUs: 110_000_000, utcUs: 110_000_000), backfilled: false)
check(replayed.reading(source: .controller)?.acquisition.originalTimeUs == 100_000_000)
let phase = ActivityPhases.shared.resolve(
  input: ActivityPhaseInput(
    phase: "completed", watchOwned: true, hasCutoff: true, ownerPhase: "running", stopOutcome: "rejected",
    verified: false, pendingStop: false, phoneStopping: false, closedWithoutWatch: false))
check(phase == "completed")
let finishing = try ActivityPresentations.shared.project(
  input: ActivityInput(
    rideId: ride, publicationSequence: 1, controlToken: token, phase: "finishing", pendingAction: nil,
    timerSeconds: KotlinDouble(value: 10)), state: state, clock: now)
check(finishing.bikeExpiresAtUs?.int64Value == 106_000_000 && finishing.staleDateUs?.int64Value == 106_000_000)
check(finishing.heartRateExpiresAtUs?.int64Value == 115_000_000)
let android = MeasurementSource(source: .phone, device: .androidphone, provider: .healthconnect, dataOrigin: nil)
check(
  try PresentationFormatting.shared.distance(
    meters: KotlinDouble(value: 1234.56), source: "gps:phone", partial: true, estimated: false,
    descriptors: [android]
  ).caption == "GPS · Android phone · Partial")
check(
  (try? PresentationFormatting.shared.distance(
    meters: KotlinDouble(value: 1234.56), source: "health:phone", partial: true, estimated: false,
    descriptors: [android])) == nil)
let start = try RideActions.shared.admit(
  state: ActionPresentationState(pending: nil, error: nil),
  request: ActionRequest(commandId: "start", rideId: nil, action: .start, confirmation: nil), phase: "ready",
  currentRideId: nil, foreignOwner: false, nativePendingAction: nil, startContext: startContext(), surface: .rider,
  adapterBusy: false)
check(start.admitted)
let stop = try RideActions.shared.admit(
  state: ActionPresentationState(pending: nil, error: nil),
  request: ActionRequest(commandId: "stop", rideId: ride, action: .stopunconfirmed, confirmation: nil),
  phase: "preparing", currentRideId: ride, foreignOwner: false, nativePendingAction: nil, startContext: startContext(),
  surface: .rider, adapterBusy: false)
check(
  stop.admitted
    && RideActions.shared.complete(state: stop.next, commandId: "stop", rideId: ride, error: nil).haptic == "none")
let earlierReplay = try LiveReading(
  acquisition: LiveAcquisition(
    observationId: "observation-a", sequence: KotlinLong(value: 3), originalTimeUs: 90_000_000,
    epoch: "process-a", monotonicUs: 90_000_000), values: ["humanPowerW": KotlinDouble(value: 300)])
let earlier = try LivePresentation.shared.observe(
  state: state, rideId: ride, source: .controller, reading: earlierReplay, clock: now, backfilled: true)
check(earlier.reading(source: .controller)?.acquisition.monotonicUs == 90_000_000)
check(earlier.reading(source: .controller)?.acquisition.originalTimeUs == 100_000_000)
check(earlier.reading(source: .controller)?.values["humanPowerW"]?.doubleValue == 200)
for context in [
  startContext(surface: .nativeowner), startContext(surface: .nativeowner, recovering: true),
  startContext(surface: .nativeowner, unresolvedOwner: true), startContext(surface: .nativeowner, hasSession: true),
] {
  let reason = RideStarts.shared.rejection(phase: "finished", context: context)
  let admitted = try RideActions.shared.admit(
    state: ActionPresentationState(pending: nil, error: nil),
    request: ActionRequest(commandId: "second", rideId: nil, action: .start, confirmation: nil), phase: "finished",
    currentRideId: nil, foreignOwner: false, nativePendingAction: nil, startContext: context, surface: .rider,
    adapterBusy: false)
  check(admitted.admitted == (reason == nil))
  check(admitted.admitted == !(context.recovering || context.unresolvedOwner || context.hasSession))
}
check(MetricCatalog.shared.acceptsSpeedUnit(value: "mph"))
check(!MetricCatalog.shared.acceptsSpeedUnit(value: "km"))
check(ProviderNames.shared.fromWire(value: "healthConnect") == "Health Connect")
print("Presentation Swift: \(checks) assertions passed")
