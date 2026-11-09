import CoreBluetooth
import Foundation

protocol CentralSeam: AnyObject {
  var state: CBManagerState { get }
  func scan(services: [CBUUID])
  func stopScan()
  func retrieve(_ identifier: UUID) -> PeripheralSeam?
  func connect(_ peripheral: PeripheralSeam)
  func cancel(_ peripheral: PeripheralSeam)
}

protocol PeripheralSeam: AnyObject {
  var identifier: UUID { get }
  var name: String? { get }
  var state: CBPeripheralState { get }
  var services: [ServiceSeam]? { get }
  var canSendWriteWithoutResponse: Bool { get }
  var maximumWriteWithoutResponse: Int { get }
  func discoverServices(_ services: [CBUUID]?)
  func discoverCharacteristics(_ characteristics: [CBUUID]?, for service: ServiceSeam)
  func enableNotifications(_ characteristic: CharacteristicSeam)
  func writeWithoutResponse(_ data: Data, for characteristic: CharacteristicSeam)
}

protocol ServiceSeam: AnyObject {
  var uuid: CBUUID { get }
  var characteristics: [CharacteristicSeam]? { get }
}

protocol CharacteristicSeam: AnyObject {
  var uuid: CBUUID { get }
  var properties: CBCharacteristicProperties { get }
  var value: Data? { get }
  var isNotifying: Bool { get }
}

protocol CentralSeamEvents: AnyObject {
  func centralStateChanged(_ state: CBManagerState)
  func centralRestored(_ peripherals: [PeripheralSeam])
  func centralDiscovered(_ peripheral: PeripheralSeam, advertisement: [String: Any], rssi: Int)
  func centralConnected(_ peripheral: PeripheralSeam)
  func centralFailedToConnect(_ peripheral: PeripheralSeam, error: Error?)
  func centralDisconnected(_ peripheral: PeripheralSeam, reconnecting: Bool, error: Error?)
  func peripheralDiscoveredServices(_ peripheral: PeripheralSeam, error: Error?)
  func peripheralDiscoveredCharacteristics(_ peripheral: PeripheralSeam, service: ServiceSeam, error: Error?)
  func peripheralUpdatedNotificationState(
    _ peripheral: PeripheralSeam, characteristic: CharacteristicSeam, error: Error?)
  func peripheralUpdatedValue(_ peripheral: PeripheralSeam, characteristic: CharacteristicSeam, error: Error?)
  func peripheralWrote(_ peripheral: PeripheralSeam, characteristic: CharacteristicSeam, error: Error?)
  func peripheralModifiedServices(_ peripheral: PeripheralSeam, invalidated: [CBUUID])
  func peripheralReadyToSend(_ peripheral: PeripheralSeam)
}

final class CoreBluetoothCentral: NSObject, CentralSeam, CBCentralManagerDelegate, CBPeripheralDelegate {
  private let events: CentralSeamEvents
  private var manager: CBCentralManager?
  private var peripherals: [UUID: SystemPeripheral] = [:]

  init(events: CentralSeamEvents, queue: DispatchQueue, restoreIdentifier: String) {
    self.events = events
    super.init()
    var options: [String: Any] = [CBCentralManagerOptionShowPowerAlertKey: true]
    #if os(iOS)
      options[CBCentralManagerOptionRestoreIdentifierKey] = restoreIdentifier
    #endif
    manager = CBCentralManager(delegate: self, queue: queue, options: options)
  }

  var state: CBManagerState { manager?.state ?? .unknown }

  func scan(services: [CBUUID]) {
    manager?.scanForPeripherals(withServices: services, options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
  }

  func stopScan() { manager?.stopScan() }

  func retrieve(_ identifier: UUID) -> PeripheralSeam? {
    manager?.retrievePeripherals(withIdentifiers: [identifier]).first.map(wrap)
  }

  func connect(_ peripheral: PeripheralSeam) {
    guard let system = (peripheral as? SystemPeripheral)?.peripheral else { return }
    manager?.connect(system, options: [CBConnectPeripheralOptionEnableAutoReconnect: true])
  }

  func cancel(_ peripheral: PeripheralSeam) {
    guard let system = (peripheral as? SystemPeripheral)?.peripheral else { return }
    manager?.cancelPeripheralConnection(system)
  }

  private func wrap(_ peripheral: CBPeripheral) -> SystemPeripheral {
    if let known = peripherals[peripheral.identifier], known.peripheral === peripheral { return known }
    let wrapped = SystemPeripheral(peripheral)
    peripheral.delegate = self
    peripherals[peripheral.identifier] = wrapped
    return wrapped
  }

  func centralManagerDidUpdateState(_ central: CBCentralManager) { events.centralStateChanged(central.state) }

  #if os(iOS)
    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
      let restored = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? []
      events.centralRestored(restored.map(wrap))
    }
  #endif

  func centralManager(
    _ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any],
    rssi RSSI: NSNumber
  ) {
    events.centralDiscovered(wrap(peripheral), advertisement: advertisementData, rssi: RSSI.intValue)
  }

  func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
    events.centralConnected(wrap(peripheral))
  }

  func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
    events.centralFailedToConnect(wrap(peripheral), error: error)
  }

  func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
    events.centralDisconnected(wrap(peripheral), reconnecting: false, error: error)
  }

  func centralManager(
    _ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, timestamp: CFAbsoluteTime,
    isReconnecting: Bool, error: Error?
  ) {
    events.centralDisconnected(wrap(peripheral), reconnecting: isReconnecting, error: error)
  }

  func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
    events.peripheralDiscoveredServices(wrap(peripheral), error: error)
  }

  func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
    let wrapped = wrap(peripheral)
    guard let match = wrapped.service(service) else { return }
    events.peripheralDiscoveredCharacteristics(wrapped, service: match, error: error)
  }

  func peripheral(
    _ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?
  ) {
    let wrapped = wrap(peripheral)
    guard let match = wrapped.characteristic(characteristic) else { return }
    events.peripheralUpdatedNotificationState(wrapped, characteristic: match, error: error)
  }

  func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
    let wrapped = wrap(peripheral)
    guard let match = wrapped.characteristic(characteristic) else { return }
    events.peripheralUpdatedValue(wrapped, characteristic: match, error: error)
  }

  func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
    let wrapped = wrap(peripheral)
    guard let match = wrapped.characteristic(characteristic) else { return }
    events.peripheralWrote(wrapped, characteristic: match, error: error)
  }

  func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
    events.peripheralModifiedServices(wrap(peripheral), invalidated: invalidatedServices.map(\.uuid))
  }

  func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
    events.peripheralReadyToSend(wrap(peripheral))
  }
}

private final class SystemPeripheral: PeripheralSeam {
  let peripheral: CBPeripheral
  private var wrappedServices: [ObjectIdentifier: SystemService] = [:]

  init(_ peripheral: CBPeripheral) { self.peripheral = peripheral }

  var identifier: UUID { peripheral.identifier }
  var name: String? { peripheral.name }
  var state: CBPeripheralState { peripheral.state }
  var services: [ServiceSeam]? { peripheral.services?.map { service($0) ?? SystemService($0) } }
  var canSendWriteWithoutResponse: Bool { peripheral.canSendWriteWithoutResponse }
  var maximumWriteWithoutResponse: Int { peripheral.maximumWriteValueLength(for: .withoutResponse) }

  func service(_ service: CBService) -> SystemService? {
    let key = ObjectIdentifier(service)
    if let known = wrappedServices[key] { return known }
    let wrapped = SystemService(service)
    wrappedServices[key] = wrapped
    return wrapped
  }

  func characteristic(_ characteristic: CBCharacteristic) -> SystemCharacteristic? {
    guard let owner = characteristic.service else { return nil }
    return service(owner)?.characteristic(characteristic)
  }

  func discoverServices(_ services: [CBUUID]?) { peripheral.discoverServices(services) }

  func discoverCharacteristics(_ characteristics: [CBUUID]?, for service: ServiceSeam) {
    guard let system = (service as? SystemService)?.service else { return }
    peripheral.discoverCharacteristics(characteristics, for: system)
  }

  func enableNotifications(_ characteristic: CharacteristicSeam) {
    guard let system = (characteristic as? SystemCharacteristic)?.characteristic else { return }
    peripheral.setNotifyValue(true, for: system)
  }

  func writeWithoutResponse(_ data: Data, for characteristic: CharacteristicSeam) {
    guard let system = (characteristic as? SystemCharacteristic)?.characteristic else { return }
    peripheral.writeValue(data, for: system, type: .withoutResponse)
  }
}

private final class SystemService: ServiceSeam {
  let service: CBService
  private var wrappedCharacteristics: [ObjectIdentifier: SystemCharacteristic] = [:]

  init(_ service: CBService) { self.service = service }

  var uuid: CBUUID { service.uuid }
  var characteristics: [CharacteristicSeam]? { service.characteristics?.map(characteristic) }

  func characteristic(_ characteristic: CBCharacteristic) -> SystemCharacteristic {
    let key = ObjectIdentifier(characteristic)
    if let known = wrappedCharacteristics[key] { return known }
    let wrapped = SystemCharacteristic(characteristic)
    wrappedCharacteristics[key] = wrapped
    return wrapped
  }
}

private final class SystemCharacteristic: CharacteristicSeam {
  let characteristic: CBCharacteristic

  init(_ characteristic: CBCharacteristic) { self.characteristic = characteristic }

  var uuid: CBUUID { characteristic.uuid }
  var properties: CBCharacteristicProperties { characteristic.properties }
  var value: Data? { characteristic.value }
  var isNotifying: Bool { characteristic.isNotifying }
}
