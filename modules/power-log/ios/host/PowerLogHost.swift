import Foundation
import PowerLogCore
import UIKit

public enum PowerLogHost {
  public private(set) static var runtime: PowerLogRuntime?

  static func start(launchOptions: [UIApplication.LaunchOptionsKey: Any]?) {
    guard runtime == nil else { return }
    let ride = DispatchQueue(label: "app.powerlog.ride", qos: .utility, autoreleaseFrequency: .workItem)
    let health = DispatchQueue(label: "app.powerlog.health", qos: .utility, autoreleaseFrequency: .workItem)
    let queues = AppleQueues(
      ride: ride,
      health: health,
      maintenance: DispatchQueue(label: "app.powerlog.maintenance", qos: .background, autoreleaseFrequency: .workItem))
    let clock = AppleClock()
    let watch = watchLink(clock: clock, queue: ride)
    let ports = Ports(
      permissions: ApplePermissionGate(queue: ride),
      picker: AppleFilePicker(documents: AttachedDocuments.shared, queue: ride), device: AppleDeviceInfo(),
      ble: AppleBleCentral(clock: clock, queue: ride), location: AppleLocationSource(clock: clock, queue: ride),
      health: AppleHealthProvider(queue: health, clock: clock), keepAlive: AppleRecordingKeepAlive(queue: ride),
      tabLock: nil, persistence: nil, rideCard: rideCard(queue: ride), watch: watch, scheduler: nil,
      tones: tonePlayer(clock: clock, queue: ride))
    #if POWER_LOG_DEV_TOOLS
      let switches = PowerLogDevSwitches.read()
      let devTools: DevTools? = DevToolsFactory.shared.create(switches: { switches }, clock: clock)
    #else
      let devTools: DevTools? = nil
    #endif
    let created = AppleRuntime.shared.create(
      queues: queues, clock: clock, ports: ports, directories: directories(), devTools: devTools)
    runtime = created
    forwardLifecycle(to: created.host)
    watch?.activate()
    let reason: ApplicationLaunchReason =
      launchOptions?[.bluetoothCentrals] != nil ? .bluetoothrelaunch : .background
    launch(created.host, reason: reason, prepare: { ride.sync { created.prepareBluetooth() } }) { refusal in
      #if POWER_LOG_DEV_TOOLS
        if refusal == nil && switches.slice { devTools?.runSlice(session: created.openBridgeSession()) }
      #endif
    }
    #if POWER_LOG_DEV_TOOLS
      PowerLogDevSwitches.arm(switches)
    #endif
  }

  public static func performRideCardIntent(
    rideId: String, token: String, phase: String, action: String,
    continueInForeground: () async throws -> Void
  ) async throws {
    try await performRideCardIntent(
      host: runtime?.host, rideId: rideId, token: token, phase: phase, action: action,
      continueInForeground: continueInForeground)
  }

  static func performRideCardIntent(
    host: HostFacade?, rideId: String, token: String, phase: String, action: String,
    continueInForeground: () async throws -> Void
  ) async throws {
    let outcome = await withCheckedContinuation { continuation in
      rideCardIntent(host: host, rideId: rideId, token: token, phase: phase, action: action) {
        continuation.resume(returning: $0)
      }
    }
    switch outcome {
    case .done:
      if action == DomainRideAction.finish.name { try await continueInForeground() }
    case .rideChanged:
      return
    case .failed:
      throw RideCardIntentFailed()
    }
  }

  static func rideCardIntent(
    host: HostFacade?, rideId: String, token: String, phase: String, action: String,
    completion: @escaping (RideCardIntentOutcome) -> Void
  ) {
    guard let host, let state = DomainRideCardState.entries.first(where: { $0.name == phase }),
      let control = DomainRideAction.entries.first(where: { $0.name == action })
    else { return completion(.failed) }
    host.rideCardIntent(
      intent: ApplicationRideCardIntent(rideId: rideId, token: token, phase: state, action: control)
    ) { failure in
      guard let failure else { return completion(.done) }
      completion(failure.code == .ridechanged ? .rideChanged : .failed)
    }
  }

  static func rideCard(queue: DispatchQueue) -> AppleRideCard {
    #if POWER_LOG_DEV_TOOLS && os(iOS)
      if let acquired = PowerLogActivityQualification.acquiredAt() {
        return PowerLogActivityQualification.start(
          acquired: acquired, queue: queue,
          running: ProcessInfo.processInfo.arguments.contains("--activity-qualification-running"))
      }
    #endif
    return AppleRideCard(queue: queue)
  }

  static func tonePlayer(clock: AppleClock, queue: DispatchQueue) -> ApplicationTonePlayer? {
    #if os(iOS)
      AppleTonePlayer(clock: clock, queue: queue)
    #else
      nil
    #endif
  }

  static func watchLink(clock: AppleClock, queue: DispatchQueue) -> AppleWatchLink? {
    #if os(iOS)
      AppleWatchLink.supported ? AppleWatchLink(clock: clock, queue: queue) : nil
    #else
      nil
    #endif
  }

  static func forwardLifecycle(
    to host: HostFacade, from center: NotificationCenter = .default,
    tasks: BackgroundTasks = ApplicationBackgroundTasks()
  ) {
    PowerLogForeground.forward(
      to: host, from: center, active: UIApplication.didBecomeActiveNotification,
      inactive: UIApplication.willResignActiveNotification)
    PowerLogBackground.forward(
      to: host, from: center, background: UIApplication.didEnterBackgroundNotification,
      terminate: UIApplication.willTerminateNotification, tasks: tasks)
  }

  static func launch(
    _ host: HostFacade, reason: ApplicationLaunchReason, prepare: () -> Void,
    done: @escaping (DomainRefusalDetail?) -> Void
  ) {
    if reason == .bluetoothrelaunch { prepare() }
    host.launch(context: ApplicationLaunchContext(reason: reason), done: done)
  }

  static func directories(_ files: FileManager = .default) -> AppleDirectories {
    let support = files.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent(
      "PowerLog", isDirectory: true)
    let caches = files.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent(
      "PowerLog", isDirectory: true)
    return AppleDirectories(
      archive: support.path, cache: caches.path,
      diagnostics: support.appendingPathComponent("diagnostics", isDirectory: true).path)
  }
}

public struct RideCardIntentFailed: Error {}

enum RideCardIntentOutcome {
  case done
  case rideChanged
  case failed
}

#if POWER_LOG_DEV_TOOLS
  enum PowerLogDevSwitches {
    static func read(_ defaults: UserDefaults = .standard) -> DevSwitches {
      let arguments = defaults.volatileDomain(forName: UserDefaults.argumentDomain)
      func seconds(_ key: String) -> KotlinDouble? {
        (arguments[key] as? String).flatMap(Double.init).map { KotlinDouble(value: $0) }
      }
      return DevSwitches.companion.fromSeconds(
        bike: defaults.bool(forKey: "PowerLogDevBike"), longRide: defaults.bool(forKey: "PowerLogDevLongRide"),
        slice: defaults.bool(forKey: "PowerLogDevSlice"), crashAfterSeconds: seconds("PowerLogDevCrashAfterSeconds"),
        killAfterSeconds: seconds("PowerLogDevKillAfterSeconds"))
    }

    static func arm(_ switches: DevSwitches) {
      if let crash = switches.crashAfterUs?.int64Value {
        DispatchQueue.main.asyncAfter(deadline: .now() + .microseconds(Int(crash))) { DevToolsFactory.shared.crash() }
      }
      if let kill = switches.killAfterUs?.int64Value {
        DispatchQueue.main.asyncAfter(deadline: .now() + .microseconds(Int(kill))) {
          _ = Darwin.kill(getpid(), SIGKILL)
        }
      }
    }
  }
#endif
