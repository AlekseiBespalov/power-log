import Foundation

struct WatchOptions: Equatable {
  enum GPS: Int { case auto = 0, on = 1, off = 2 }

  var indoor: Bool
  var saveToHealth: Bool
  var gps: GPS

  static let defaults = WatchOptions(indoor: false, saveToHealth: true, gps: .auto)

  var recordGps: Bool? {
    switch gps {
    case .auto: nil
    case .on: true
    case .off: false
    }
  }

  var startOptions: WatchStartOptions {
    WatchStartOptions(indoor: indoor, saveToHealth: saveToHealth, recordGps: recordGps)
  }
}

protocol WatchOptionsStore: AnyObject {
  func load() -> WatchOptions
  func save(_ options: WatchOptions)
}

final class DefaultsOptionsStore: WatchOptionsStore {
  private let defaults: UserDefaults

  init(defaults: UserDefaults = .standard) { self.defaults = defaults }

  func load() -> WatchOptions {
    let fallback = WatchOptions.defaults
    return WatchOptions(
      indoor: defaults.object(forKey: Keys.indoor) as? Bool ?? fallback.indoor,
      saveToHealth: defaults.object(forKey: Keys.saveToHealth) as? Bool ?? fallback.saveToHealth,
      gps: (defaults.object(forKey: Keys.gps) as? Int).flatMap(WatchOptions.GPS.init) ?? fallback.gps)
  }

  func save(_ options: WatchOptions) {
    defaults.set(options.indoor, forKey: Keys.indoor)
    defaults.set(options.saveToHealth, forKey: Keys.saveToHealth)
    defaults.set(options.gps.rawValue, forKey: Keys.gps)
  }

  private enum Keys {
    static let indoor = "ride.indoor"
    static let saveToHealth = "ride.saveToHealth"
    static let gps = "ride.recordGPS"
  }
}
