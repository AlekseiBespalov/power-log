import Foundation
import PowerLogCore

func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
  if !condition() {
    FileHandle.standardError.write(Data("AppleDeviceInfo: \(message)\n".utf8))
    exit(1)
  }
}

let info = AppleDeviceInfo()
let snapshot = info.snapshot()
expect(snapshot.platform == DomainPlatform.ios, "the platform is ios")
expect(snapshot.manufacturer == "Apple", "the manufacturer is Apple")
expect(!snapshot.model.isEmpty, "utsname gives the model")
expect(snapshot.osVersion.split(separator: ".").count == 3, "the OS version has three parts")
expect(snapshot.sdkLevel == nil, "the iPhone reports no SDK level")

let unitedStates = Locale(identifier: "en_US")
let facts = AppleDeviceInfo.facts(unitedStates)
expect(facts.region == "US", "the region comes from the locale")
expect(facts.measurementSystem == DomainMeasurementSystem.us, "the US measurement system is us")
expect(facts.temperature == nil, "the iPhone reports no temperature unit")
expect(
  AppleDeviceInfo.facts(Locale(identifier: "en_GB")).measurementSystem == DomainMeasurementSystem.uk, "en_GB is uk")
expect(
  AppleDeviceInfo.facts(Locale(identifier: "de_DE")).measurementSystem == DomainMeasurementSystem.metric,
  "de_DE is metric")
expect(!AppleDeviceInfo.uses24h(unitedStates), "en_US writes the hour with a day period")
expect(AppleDeviceInfo.uses24h(Locale(identifier: "de_DE")), "de_DE writes a 24-hour clock")
expect(AppleDeviceInfo(locale: { Locale(identifier: "fr_FR") }).snapshot().locale == "fr-FR", "the locale is BCP 47")

expect(AppleDeviceInfo.thermal(.nominal) == ApplicationThermalLevel.nominal, "nominal")
expect(AppleDeviceInfo.thermal(.fair) == ApplicationThermalLevel.fair, "fair")
expect(AppleDeviceInfo.thermal(.serious) == ApplicationThermalLevel.serious, "serious")
expect(AppleDeviceInfo.thermal(.critical) == ApplicationThermalLevel.critical, "critical")

let conditions = info.conditions()
expect(conditions.powerSave != nil, "the low-power flag is reported")
expect(conditions.thermal != nil, "the thermal state is reported")
let firstCpu = conditions.processCpuUs!.int64Value
var spin = 0.0
for value in 0..<2_000_000 { spin += sin(Double(value)) }
expect(spin.isFinite, "the spin ran")
expect(info.conditions().processCpuUs!.int64Value > firstCpu, "process CPU time grows with work")
expect(conditions.footprintBytes!.int64Value > 0, "the footprint is reported")
expect(
  conditions.ignoringBatteryOptimizations == nil && conditions.backgroundRestricted == nil,
  "Android-only fields are null")
expect(conditions.standbyBucket == nil, "the standby bucket is Android's")
expect(info.previousExit() == nil, "the iPhone logs no previous exit")
print("AppleDeviceInfo: 25 assertions passed")
