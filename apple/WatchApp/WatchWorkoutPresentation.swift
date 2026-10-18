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
