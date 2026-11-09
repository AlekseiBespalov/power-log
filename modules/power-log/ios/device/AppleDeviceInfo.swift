import Darwin
import Foundation
import PowerLogCore

final class AppleDeviceInfo: NSObject, ApplicationDeviceInfo {
  private let bundle: Bundle
  private let locale: () -> Locale

  init(bundle: Bundle = .main, locale: @escaping () -> Locale = { .current }) {
    self.bundle = bundle
    self.locale = locale
  }

  func snapshot() -> ApplicationDeviceSnapshot {
    let version = ProcessInfo.processInfo.operatingSystemVersion
    let current = locale()
    return ApplicationDeviceSnapshot(
      platform: .ios,
      manufacturer: "Apple",
      model: Self.machine(),
      osVersion: "\(version.majorVersion).\(version.minorVersion).\(version.patchVersion)",
      sdkLevel: nil,
      appVersion: bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "",
      appBuild: bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "",
      uses24h: Self.uses24h(current),
      locale: current.identifier(.bcp47),
      localeFacts: Self.facts(current))
  }

  func conditions() -> ApplicationDeviceConditions {
    let process = ProcessInfo.processInfo
    return ApplicationDeviceConditions(
      powerSave: KotlinBoolean(value: process.isLowPowerModeEnabled),
      thermal: Self.thermal(process.thermalState),
      processCpuUs: KotlinLong(value: Int64(clock_gettime_nsec_np(CLOCK_PROCESS_CPUTIME_ID) / 1_000)),
      footprintBytes: Self.footprint().map { KotlinLong(value: $0) },
      ignoringBatteryOptimizations: nil,
      backgroundRestricted: nil,
      standbyBucket: nil)
  }

  func previousExit() -> ApplicationExitReport? { nil }

  static func uses24h(_ locale: Locale) -> Bool {
    !(DateFormatter.dateFormat(fromTemplate: "j", options: 0, locale: locale) ?? "").contains("a")
  }

  static func facts(_ locale: Locale) -> DomainLocaleFacts {
    let system: DomainMeasurementSystem? =
      switch locale.measurementSystem {
      case .us: .us
      case .uk: .uk
      case .metric: .metric
      default: nil
      }
    return DomainLocaleFacts(region: locale.region?.identifier, measurementSystem: system, temperature: nil)
  }

  static func thermal(_ state: ProcessInfo.ThermalState) -> ApplicationThermalLevel {
    switch state {
    case .nominal: .nominal
    case .fair: .fair
    case .serious: .serious
    default: .critical
    }
  }

  private static func machine() -> String {
    var system = utsname()
    uname(&system)
    return withUnsafeBytes(of: system.machine) { String(decoding: $0.prefix { $0 != 0 }, as: UTF8.self) }
  }

  private static func footprint() -> Int64? {
    var info = task_vm_info_data_t()
    var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size)
    let result = withUnsafeMutablePointer(to: &info) {
      $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
        task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
      }
    }
    return result == KERN_SUCCESS ? Int64(info.phys_footprint) : nil
  }
}
