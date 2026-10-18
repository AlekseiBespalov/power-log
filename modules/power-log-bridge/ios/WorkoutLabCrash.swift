#if POWER_LOG_LAB
  import Foundation

  enum WorkoutLabCrash {
    static var isSDKProbe: Bool { UserDefaults.standard.string(forKey: "PowerLogLabCase") != nil }
    static var transaction: ((String, PowerLogDatabase) throws -> Void)?
  }
#endif
