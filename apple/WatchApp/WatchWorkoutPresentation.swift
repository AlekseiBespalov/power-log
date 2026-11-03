import Foundation

enum WatchRideAction { case start, pause, resume, lap, stop }
enum WatchIdlePresentation {
  case ready, starting, saved
  var showsProgress: Bool { self == .starting }
  var title: String { self == .starting ? "Starting with phone…" : self == .saved ? "Ride finished" : "Ready to ride" }
}
enum WatchRidePreferences {
  struct Defaults {
    let indoor = false
    let saveToHealth: NSNumber? = true
  }
  struct Options { let recordGPS: Bool }
  static let defaults = Defaults()
  static let defaultGPSSetting = 0
  static func resolve(indoor: Bool, saveToHealth: Bool, gpsSetting: Int) throws -> Options {
    Options(recordGPS: gpsSetting == 0 ? !indoor : gpsSetting == 1)
  }
}
struct WatchSourcePresentation {
  let label: String
  let tone: String
}
struct WatchReadingsProjection {
  let power: String
  let cadence: String
  let heart: String
  let controller: WatchSourcePresentation?
  let gps: String
}
struct WatchMetricAppearance {
  struct Declaration {
    let label: String
    let unit: String
  }
  let declaration: Declaration
  let red: Double
  let green: Double
  let blue: Double
  static let power = Self(declaration: Declaration(label: "Rider power", unit: "W"), red: 1, green: 0.7, blue: 0)
  static let cadence = Self(declaration: Declaration(label: "Cadence", unit: "rpm"), red: 0.3, green: 0.8, blue: 1)
  static let heart = Self(declaration: Declaration(label: "Heart rate", unit: "bpm"), red: 1, green: 0.3, blue: 0.4)
}
enum WatchRideText {
  static let startUnconfirmed = "Power Log couldn't confirm the start. Start again."
  static let startExpired = "This start arrived too late to begin the ride. Start again."
  static let phoneUnconfirmed = "Your phone didn't confirm. Check Power Log on your phone, then try again."
  static let rideChanged = "This ride changed. Check the current ride, then try again."
  static let endedUnreachable = "Your phone isn't reachable, so the ride ended on your Watch. Finish it on your phone."
  static let endedUnconfirmed = "Your phone didn't confirm, so the ride ended on your Watch. Check it on your phone."
  static let interrupted = "Your last ride was interrupted. Finish or discard it on your phone."
  static let readingNotSent =
    "A Watch reading couldn't be sent to your phone. Keep riding; the next readings will be sent."

  static func health(_ state: String) -> String {
    switch state {
    case "saved": return "Apple Health saved"
    case "pending": return "Apple Health save pending"
    case "notRequested": return "Saved only in Power Log"
    case "failed", "notSaved": return "Apple Health not saved"
    case "unavailable": return "Apple Health unavailable"
    case "discarded": return "Apple Health discarded"
    default: return "Apple Health save unconfirmed"
    }
  }

  static func startFailure(_ error: Error) -> String {
    let native = error as NSError
    // HKError.errorAuthorizationDenied (4) and .errorDatabaseInaccessible (6).
    if native.domain == "com.apple.healthkit", native.code == 4 {
      return "Power Log can't save to Health. Allow it in the Health app, then start again."
    }
    if native.domain == "com.apple.healthkit", native.code == 6 {
      return "Health data is unavailable while this device is locked. Unlock it, then try again."
    }
    return "The ride didn't start. Start again."
  }
}
