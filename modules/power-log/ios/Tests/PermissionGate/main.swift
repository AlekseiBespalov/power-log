import CoreBluetooth
import CoreLocation
import Foundation
import PowerLogCore

func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
  if !condition() {
    FileHandle.standardError.write(Data("ApplePermissionGate: \(message)\n".utf8))
    exit(1)
  }
}

final class FakeSystem: PermissionSystem {
  var bluetoothState = CBManagerAuthorization.notDetermined
  var locationState = LocationAuthorization(status: .notDetermined, precise: true, servicesOn: true)
  var bluetoothPrompts: [() -> Void] = []
  var locationPrompts: [() -> Void] = []
  func bluetooth() -> CBManagerAuthorization { bluetoothState }
  func promptBluetooth(_ answered: @escaping () -> Void) { bluetoothPrompts.append(answered) }
  func location(_ read: @escaping (LocationAuthorization) -> Void) { read(locationState) }
  func promptLocation(_ answered: @escaping () -> Void) { locationPrompts.append(answered) }
}

final class Rig {
  let system = FakeSystem()
  var mainQueue: [() -> Void] = []
  var answers: [ApplicationPermissionStates] = []
  var failures: [ApplicationPortError] = []
  lazy var gate = ApplePermissionGate(system: system, main: { self.mainQueue.append($0) })
  var reply: ApplicationReply<ApplicationPermissionStates> {
    ApplicationReply(onOk: { self.answers.append($0!) }, onFailed: { self.failures.append($0) })
  }

  func onMain() {
    while !mainQueue.isEmpty { mainQueue.removeFirst()() }
  }
}

let status = Rig()
status.gate.status(reply: status.reply)
expect(status.answers.isEmpty, "a status answers after the call returns")
status.onMain()
let undecided = status.answers[0]
expect(undecided.bluetooth == .notdetermined, "Bluetooth is undecided")
expect(undecided.location == .notdetermined, "location is undecided")
expect(undecided.preciseLocation == nil, "precision is unknown before a grant")
expect(undecided.locationServicesOn?.boolValue == true, "location services are on")
expect(undecided.notifications == nil, "the iPhone reports no notification permission")

let bluetooth = Rig()
bluetooth.gate.request(kinds: [.bluetooth], reply: bluetooth.reply)
bluetooth.onMain()
expect(bluetooth.system.bluetoothPrompts.count == 1, "an undecided Bluetooth permission prompts")
expect(bluetooth.answers.isEmpty, "the request waits for the prompt's manager")
bluetooth.system.bluetoothState = .allowedAlways
bluetooth.system.bluetoothPrompts[0]()
bluetooth.onMain()
expect(bluetooth.answers.first?.bluetooth == .granted, "the request answers at the manager's first state")
expect(bluetooth.system.locationPrompts.isEmpty, "a Bluetooth request asks nothing about location")

let both = Rig()
both.gate.request(kinds: [.bluetooth, .location], reply: both.reply)
both.onMain()
both.system.bluetoothState = .denied
both.system.bluetoothPrompts[0]()
both.onMain()
expect(both.system.locationPrompts.count == 1, "location is asked after Bluetooth")
both.gate.request(kinds: [.location], reply: both.reply)
both.onMain()
expect(both.failures.map(\.kind) == [.busy], "a request while one runs is busy")
both.system.locationState = LocationAuthorization(status: .authorizedAlways, precise: false, servicesOn: true)
both.system.locationPrompts[0]()
both.onMain()
let answered = both.answers[0]
expect(answered.bluetooth == .denied, "a refused Bluetooth prompt reads denied")
expect(answered.location == .granted, "a granted location reads granted")
expect(answered.preciseLocation?.boolValue == false, "reduced accuracy is not precise")

let decided = Rig()
decided.system.bluetoothState = .restricted
decided.system.locationState = LocationAuthorization(status: .restricted, precise: true, servicesOn: false)
decided.gate.request(kinds: [.bluetooth, .location, .notifications], reply: decided.reply)
decided.onMain()
expect(decided.system.bluetoothPrompts.isEmpty && decided.system.locationPrompts.isEmpty, "decided kinds never prompt")
expect(decided.answers.first?.bluetooth == .restricted, "a restricted radio reads restricted")
expect(decided.answers.first?.location == .restricted, "restricted location reads restricted")
expect(decided.answers.first?.locationServicesOn?.boolValue == false, "location services off is reported")
decided.gate.request(kinds: [.location], reply: decided.reply)
decided.onMain()
expect(decided.answers.count == 2, "a finished request frees the gate")

expect(ApplePermissionGate.state(CLAuthorizationStatus.authorizedAlways) == .granted, "always is granted")
expect(ApplePermissionGate.state(CLAuthorizationStatus.denied) == .denied, "denied")
print("ApplePermissionGate: 25 assertions passed")
