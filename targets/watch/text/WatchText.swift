import Foundation

enum WatchText {
  static let missing = "—"

  private static let failures: [String: (message: String, nextStep: String)] = [
    "actionPending": ("Another ride action is still running.", "Wait for it to finish."),
    "actionUnavailable": ("This action isn't available right now.", "Check the current ride, then try again."),
    "bikeRequired": ("Connect your bike to start.", ""),
    "gpsRequired": (
      "This distance source needs GPS recording.", "Turn on GPS route, or choose another distance source in Settings."
    ),
    "healthDenied": ("Power Log can't save to Health.", "Allow it in the Health app, then start again."),
    "healthLocked": ("Health data is unavailable while this device is locked.", "Unlock it, then try again."),
    "healthUnavailable": ("Health isn't available on this device.", "Turn off Save to Health, then start again."),
    "locationDenied": (
      "Power Log can't use your location.", "Allow location in Settings, or turn off GPS for this ride."
    ),
    "ownerForeign": ("Another browser tab is recording this ride.", "Use that tab to control it."),
    "rideActive": ("A ride is already in progress.", "Finish or discard it before starting another."),
    "rideChanged": ("This ride changed.", "Check the current ride, then try again."),
    "settingsSaveFailed": ("Your settings weren\u{2019}t saved.", "Try again."),
    "settingsUnreadable": (
      "Power Log couldn't read your saved settings.", "Open Settings on your iPhone and reset them, then start again."
    ),
    "startCancelled": ("This start was cancelled before the ride began.", "Start again."),
    "startExpired": ("This start arrived too late to begin the ride.", "Start again."),
    "startInterrupted": ("The ride didn't start.", "Start again."),
    "startUnconfirmed": ("Power Log couldn't confirm the start.", "Start again."),
    "storageBusy": ("Power Log is busy saving your ride.", "Try again in a moment."),
    "storageFailed": ("Power Log couldn't save this change.", "Try again. Your recorded data is kept."),
    "storageFull": ("This device is out of storage.", "Free up space, then try again."),
    "uiUnavailable": ("Power Log couldn't open this screen.", "Open Power Log, then try again."),
    "unknown": ("Something went wrong.", "Try again. Your recorded data is kept."),
    "watchBusy": (
      "Your Apple Watch is still busy with another ride.", "Finish or discard it on your Apple Watch, then start again."
    ),
    "watchLaunchFailed": (
      "Power Log couldn't open on your Apple Watch.",
      "Unlock your Apple Watch and keep it near your iPhone, then start again."
    ),
    "watchMessageInvalid": (
      "Your Apple Watch sent something Power Log couldn't read.",
      "Install the same Power Log build on your iPhone and Apple Watch."
    ),
    "watchNotInstalled": (
      "Power Log isn't installed on your Apple Watch.",
      "Install it from the Watch app on your iPhone, or turn off Apple Watch in Settings."
    ),
    "watchStartUnconfirmed": (
      "Your Apple Watch didn't confirm the start.",
      "Open Power Log on your Apple Watch, keep it unlocked and nearby, then start again."
    ),
    "watchVersionMismatch": (
      "Power Log on your Apple Watch is a different version.",
      "Install the same Power Log build on your iPhone and Apple Watch, or turn off Apple Watch in Settings."
    ),
  ]

  static var failureCodes: [String] { Array(failures.keys) }

  static func failure(_ code: String) -> (message: String, nextStep: String) {
    failures[code] ?? failures["unknown"]!
  }

  static func failureLine(_ code: String) -> String {
    let text = failure(code)
    return text.nextStep.isEmpty ? text.message : "\(text.message) \(text.nextStep)"
  }

  static func issue(_ issue: WatchIssue) -> String {
    switch issue {
    case .failure(let code): failureLine(code)
    case .phoneUnreachableAtStart: "Open Power Log on your phone to start a ride."
    case .commandUnconfirmed: "Your phone didn't confirm. Check Power Log on your phone, then try again."
    case .endedLocallyUnreachable:
      "Your phone isn't reachable, so the ride ended on your Watch. Finish it on your phone."
    case .endedLocallyUnconfirmed:
      "Your phone didn't confirm, so the ride ended on your Watch. Check it on your phone."
    case .orphanRecovered: "Your last ride was interrupted. Finish or discard it on your phone."
    }
  }

  static func reason(_ reason: WatchReason) -> String {
    switch reason {
    case .phoneUnreachable: "Your phone isn't reachable."
    case .commandPending: "Waiting for your phone."
    case .sessionBusy: "Apple Health is still finishing the last ride."
    case .noActiveRide: "No ride in progress."
    }
  }

  static func health(_ outcome: HealthOutcome?) -> String? {
    switch outcome {
    case nil: nil
    case .saved: "Apple Health saved"
    case .pending: "Apple Health save pending"
    case .notRequested: "Saved only in Power Log"
    default: "Apple Health save unconfirmed"
    }
  }

  static func duration(us: Int64?) -> String {
    guard let us else { return missing }
    let seconds = us > 0 ? us / 1_000_000 : 0
    let hours = seconds / 3_600
    let clock = String(format: "%02lld:%02lld", seconds / 60 % 60, seconds % 60)
    return hours > 0 ? "\(hours):\(clock)" : clock
  }

  static func timer(_ timer: RideTimer?, at now: Date) -> String {
    guard let timer else { return missing }
    switch timer.state {
    case .running:
      guard let origin = timer.originUtcUs else { return missing }
      return duration(us: HealthInstant.us(now) - origin)
    case .frozen: return duration(us: timer.baseUs)
    case .unavailable: return missing
    }
  }

  static func distance(_ meters: Double?, units: UnitSystem) -> (text: String, symbol: String) {
    let symbol = units == .metric ? "km" : "mi"
    guard let meters, meters.isFinite, meters >= 0 else { return (missing, symbol) }
    return (fixed(meters * (units == .metric ? 0.001 : 1 / 1_609.344), places: 2), symbol)
  }

  static func whole(_ value: Double?) -> String {
    guard let value, value.isFinite else { return missing }
    return fixed(value, places: 0)
  }

  static func distanceSource(_ source: DistanceSource?) -> String {
    source == .controller ? "Controller" : "Phone GPS"
  }

  static func controller(fresh: Bool) -> String { fresh ? "Controller" : "Controller unavailable" }

  static func gps(_ recordGps: Bool) -> String { recordGps ? "Phone GPS" : "GPS off" }

  static func rideType(indoor: Bool) -> String { indoor ? "Indoor ride" : "Outdoor ride" }

  static func lap(_ lapCount: Int) -> String? { lapCount > 0 ? "Lap \(lapCount + 1)" : nil }

  // Kit's DecimalText.fixed rounds the double's exact decimal expansion half up, which printf's full expansion gives.
  static func fixed(_ value: Double, places: Int) -> String {
    let negative = value < 0
    let digits = String(format: "%.40f", abs(value))
    let point = digits.firstIndex(of: ".")!
    let whole = String(digits[..<point])
    let fraction = Array(digits[digits.index(after: point)...])
    var kept = Array(whole + String(fraction.prefix(places)))
    if fraction.count > places, fraction[places] >= "5" {
      var index = kept.count - 1
      while index >= 0 && kept[index] == "9" {
        kept[index] = "0"
        index -= 1
      }
      if index < 0 {
        kept.insert("1", at: 0)
      } else {
        kept[index] = Character(String(kept[index].wholeNumberValue! + 1))
      }
    }
    let text = String(kept)
    let shown = places == 0 ? text : "\(text.dropLast(places)).\(text.suffix(places))"
    return negative ? "-\(shown)" : shown
  }
}
