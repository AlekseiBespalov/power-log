import CoreBluetooth
import Foundation
import PowerLogCore

final class AppleBleCentral: NSObject, ApplicationBleCentral, CentralSeamEvents {
  private enum State { case connecting, up, cancelling, ended }

  private final class Link {
    let id: Int64
    let bike: String
    let profile: DomainTransportProfile
    let peripheral: PeripheralSeam
    var state = State.connecting
    var inventory = false
    var writer: CharacteristicSeam?
    var notifier: CharacteristicSeam?
    var pendingCharacteristics = 0
    var owesCanSend = false

    init(id: Int64, bike: String, profile: DomainTransportProfile, peripheral: PeripheralSeam) {
      self.id = id
      self.bike = bike
      self.profile = profile
      self.peripheral = peripheral
    }
  }

  private let clock: ApplicationClock
  private let post: (@escaping () -> Void) -> Void
  private let makeCentral: (CentralSeamEvents) -> CentralSeam
  private var central: CentralSeam?
  private var events: ApplicationBleEvents?
  private var searching = false
  private var searchInventory = false
  private var discovered: [String: PeripheralSeam] = [:]
  private var restored: [String: PeripheralSeam] = [:]
  private var link: Link?
  private var kept: Link?
  private var cancelledPending: Set<UUID> = []
  private var nextLink: Int64 = 1

  init(
    clock: ApplicationClock, post: @escaping (@escaping () -> Void) -> Void,
    makeCentral: @escaping (CentralSeamEvents) -> CentralSeam
  ) {
    self.clock = clock
    self.post = post
    self.makeCentral = makeCentral
  }

  convenience init(clock: ApplicationClock, queue: DispatchQueue) {
    self.init(
      clock: clock, post: { queue.async(execute: $0) },
      makeCentral: { CoreBluetoothCentral(events: $0, queue: queue, restoreIdentifier: Self.restoreIdentifier) })
  }

  static let restoreIdentifier = "app.powerlog.controller.central"

  func attach(bleEvents: ApplicationBleEvents) { events = bleEvents }

  func prepare() {
    guard central == nil else { return }
    central = makeCentral(self)
  }

  func radio() -> ApplicationRadioState { Self.radio(central?.state ?? .unknown) }

  func startSearch(profiles: [DomainTransportProfile], inventory: Bool) {
    stopSearch()
    guard let central, central.state == .poweredOn else {
      let failure = ApplicationPortError(kind: Self.refusal(radio()), platform: nil)
      post { [weak self] in self?.events?.searchEnded(error: failure) }
      return
    }
    searching = true
    searchInventory = inventory
    var services: [CBUUID] = []
    for profile in profiles where !services.contains(CBUUID(string: profile.service)) {
      services.append(CBUUID(string: profile.service))
    }
    central.scan(services: services)
  }

  func stopSearch() {
    guard searching else { return }
    searching = false
    central?.stopScan()
  }

  func connect(bike: String, profile: DomainTransportProfile, adopt: Bool) -> Int64 {
    let id = nextLink
    nextLink += 1
    let radio = radio()
    guard let central, radio == .poweredon else {
      fail(id, ApplicationPortError(kind: Self.refusal(radio), platform: nil))
      return id
    }
    let adoptable = adopt ? (restored[bike] ?? kept.flatMap { $0.bike == bike ? $0.peripheral : nil }) : nil
    for (other, peripheral) in restored where other != bike || adoptable == nil {
      central.cancel(peripheral)
    }
    restored = [:]
    if let earlier = kept, earlier.peripheral !== adoptable {
      central.cancel(earlier.peripheral)
    }
    kept = nil
    guard
      let peripheral = adoptable ?? discovered[bike] ?? UUID(uuidString: bike).flatMap({ central.retrieve($0) })
    else {
      fail(id, ApplicationPortError(kind: .unknownbike, platform: nil))
      return id
    }
    let created = Link(id: id, bike: bike, profile: profile, peripheral: peripheral)
    link = created
    if adoptable != nil, peripheral.state == .connected {
      created.state = .up
      post { [weak self] in self?.events?.linkUp(link: id, bike: bike) }
    } else if adoptable == nil || peripheral.state != .connecting {
      central.connect(peripheral)
    }
    return id
  }

  func ready(link id: Int64, inventory: Bool) {
    guard let current = link, current.id == id, current.state == .up else { return }
    current.inventory = inventory
    current.writer = nil
    current.notifier = nil
    current.peripheral.discoverServices(inventory ? nil : [CBUUID(string: current.profile.service)])
  }

  func send(link id: Int64, frame: DomainControllerFrame) -> ApplicationSendResult {
    guard let current = link, current.id == id, current.state == .up, let writer = current.writer else {
      return .notconnected
    }
    if Int(frame.bytes.size) > current.peripheral.maximumWriteWithoutResponse { return .toolong }
    guard current.peripheral.canSendWriteWithoutResponse else {
      current.owesCanSend = true
      return .blocked
    }
    current.peripheral.writeWithoutResponse(AppleBytes.shared.data(bytes: frame.bytes), for: writer)
    return .accepted
  }

  func cancel(link id: Int64) {
    if let reconnecting = kept, reconnecting.id == id {
      kept = nil
      cancelledPending.insert(reconnecting.peripheral.identifier)
      central?.cancel(reconnecting.peripheral)
      post { [weak self] in self?.events?.linkDown(link: id, error: nil, systemReconnecting: false) }
      return
    }
    guard let current = link, current.id == id else { return }
    switch current.state {
    case .connecting:
      current.state = .ended
      cancelledPending.insert(current.peripheral.identifier)
      central?.cancel(current.peripheral)
      post { [weak self] in self?.events?.linkDown(link: id, error: nil, systemReconnecting: false) }
    case .up:
      current.state = .cancelling
      central?.cancel(current.peripheral)
    case .cancelling, .ended:
      break
    }
  }

  func release(link id: Int64) -> Bool { false }

  func centralStateChanged(_ state: CBManagerState) {
    let radio = Self.radio(state)
    events?.radioChanged(state: radio)
    guard radio != .poweredon else { return }
    searching = false
    cancelledPending = []
    let failure = ApplicationPortError(kind: Self.refusal(radio), platform: nil)
    if let reconnect = kept {
      kept = nil
      events?.linkDown(link: reconnect.id, error: failure, systemReconnecting: false)
    }
    guard let current = link else { return }
    switch current.state {
    case .connecting:
      current.state = .ended
      events?.linkFailed(link: current.id, error: failure)
    case .up:
      current.state = .ended
      events?.linkDown(link: current.id, error: failure, systemReconnecting: false)
    case .cancelling:
      current.state = .ended
      events?.linkDown(link: current.id, error: nil, systemReconnecting: false)
    case .ended:
      break
    }
  }

  func centralRestored(_ peripherals: [PeripheralSeam]) {
    for peripheral in peripherals { restored[peripheral.identifier.uuidString] = peripheral }
  }

  func centralDiscovered(_ peripheral: PeripheralSeam, advertisement: [String: Any], rssi: Int) {
    let at = clock.read()
    guard searching else { return }
    let bike = peripheral.identifier.uuidString
    discovered[bike] = peripheral
    let services =
      (advertisement[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? [])
      + (advertisement[CBAdvertisementDataOverflowServiceUUIDsKey] as? [CBUUID] ?? [])
    let manufacturer =
      searchInventory
      ? (advertisement[CBAdvertisementDataManufacturerDataKey] as? Data).map(AppleBytes.shared.bytes) : nil
    events?.advertised(
      advertisement: ApplicationAdvertisement(
        bike: bike, name: advertisement[CBAdvertisementDataLocalNameKey] as? String ?? peripheral.name,
        rssi: KotlinInt(value: Int32(clamping: rssi)), services: services.map(Self.uuid),
        manufacturerData: manufacturer, at: at))
  }

  func centralConnected(_ peripheral: PeripheralSeam) {
    if let current = link, current.peripheral.identifier == peripheral.identifier, current.state == .connecting {
      cancelledPending.remove(peripheral.identifier)
      current.state = .up
      events?.linkUp(link: current.id, bike: current.bike)
      return
    }
    if let current = link, current.peripheral.identifier == peripheral.identifier, current.state != .ended { return }
    if kept?.peripheral.identifier == peripheral.identifier { return }
    if restored[peripheral.identifier.uuidString] != nil { return }
    central?.cancel(peripheral)
  }

  func centralFailedToConnect(_ peripheral: PeripheralSeam, error: Error?) {
    if cancelledPending.remove(peripheral.identifier) != nil { return }
    guard let current = link, current.peripheral.identifier == peripheral.identifier, current.state == .connecting
    else { return }
    current.state = .ended
    events?.linkFailed(
      link: current.id, error: ApplicationPortError(kind: .connectionfailed, platform: Self.code(error)))
  }

  func centralDisconnected(_ peripheral: PeripheralSeam, reconnecting: Bool, error: Error?) {
    if let reconnect = kept, reconnect.peripheral.identifier == peripheral.identifier {
      guard !reconnecting else { return }
      kept = nil
      events?.linkDown(
        link: reconnect.id, error: ApplicationPortError(kind: .linklost, platform: Self.code(error)),
        systemReconnecting: false)
      return
    }
    if !reconnecting, cancelledPending.remove(peripheral.identifier) != nil { return }
    guard let current = link, current.peripheral.identifier == peripheral.identifier, current.state != .ended else {
      if reconnecting, restored[peripheral.identifier.uuidString] == nil { central?.cancel(peripheral) }
      return
    }
    let previous = current.state
    current.state = .ended
    switch previous {
    case .connecting:
      events?.linkFailed(
        link: current.id, error: ApplicationPortError(kind: .connectionfailed, platform: Self.code(error)))
    case .up:
      if reconnecting { kept = current }
      events?.linkDown(
        link: current.id, error: ApplicationPortError(kind: .linklost, platform: Self.code(error)),
        systemReconnecting: reconnecting)
    case .cancelling:
      if reconnecting { central?.cancel(peripheral) }
      events?.linkDown(link: current.id, error: nil, systemReconnecting: false)
    case .ended:
      break
    }
  }

  func peripheralDiscoveredServices(_ peripheral: PeripheralSeam, error: Error?) {
    guard let current = live(peripheral) else { return }
    if let error {
      return ready(current, .servicemissing, ApplicationPortError(kind: .failed, platform: Self.code(error)))
    }
    let services = peripheral.services ?? []
    let wanted = CBUUID(string: current.profile.service)
    if current.inventory {
      current.pendingCharacteristics = services.count
      if services.isEmpty { return ready(current, .servicemissing, nil) }
      for service in services { peripheral.discoverCharacteristics(nil, for: service) }
    } else if let service = services.first(where: { $0.uuid == wanted }) {
      current.pendingCharacteristics = 1
      peripheral.discoverCharacteristics(
        [CBUUID(string: current.profile.write), CBUUID(string: current.profile.notify)], for: service)
    } else {
      ready(current, .servicemissing, nil)
    }
  }

  func peripheralDiscoveredCharacteristics(_ peripheral: PeripheralSeam, service: ServiceSeam, error: Error?) {
    guard let current = live(peripheral), current.pendingCharacteristics > 0 else { return }
    current.pendingCharacteristics -= 1
    guard current.pendingCharacteristics == 0 else { return }
    let profile = current.profile
    guard let service = peripheral.services?.first(where: { $0.uuid == CBUUID(string: profile.service) }) else {
      return ready(current, .servicemissing, nil)
    }
    let characteristics = service.characteristics ?? []
    guard
      let writer = characteristics.first(where: {
        $0.uuid == CBUUID(string: profile.write) && $0.properties.contains(.writeWithoutResponse)
      }),
      let notifier = characteristics.first(where: {
        $0.uuid == CBUUID(string: profile.notify) && $0.properties.contains(.notify)
      })
    else {
      return ready(
        current, .characteristicmissing, error.map { ApplicationPortError(kind: .failed, platform: Self.code($0)) })
    }
    current.writer = writer
    current.notifier = notifier
    peripheral.enableNotifications(notifier)
  }

  func peripheralUpdatedNotificationState(
    _ peripheral: PeripheralSeam, characteristic: CharacteristicSeam, error: Error?
  ) {
    guard let current = live(peripheral), let notifier = current.notifier, characteristic === notifier else { return }
    if error != nil || !characteristic.isNotifying {
      current.writer = nil
      current.notifier = nil
      return ready(current, .notifyfailed, error.map { ApplicationPortError(kind: .failed, platform: Self.code($0)) })
    }
    ready(current, .ready, nil)
  }

  func peripheralUpdatedValue(_ peripheral: PeripheralSeam, characteristic: CharacteristicSeam, error: Error?) {
    let at = clock.read()
    guard let current = live(peripheral), let notifier = current.notifier, characteristic === notifier else { return }
    if let error {
      events?.notificationFailed(
        link: current.id, error: ApplicationPortError(kind: .failed, platform: Self.code(error)))
      return
    }
    guard let value = characteristic.value else { return }
    events?.notified(link: current.id, bytes: AppleBytes.shared.bytes(data: value), at: at)
  }

  func peripheralWrote(_ peripheral: PeripheralSeam, characteristic: CharacteristicSeam, error: Error?) {
    guard let current = live(peripheral), let error else { return }
    events?.writeFailed(link: current.id, error: ApplicationPortError(kind: .writefailed, platform: Self.code(error)))
  }

  func peripheralModifiedServices(_ peripheral: PeripheralSeam, invalidated: [CBUUID]) {
    guard let current = live(peripheral), invalidated.contains(CBUUID(string: current.profile.service)) else {
      return
    }
    current.writer = nil
    current.notifier = nil
    events?.servicesInvalidated(link: current.id)
  }

  func peripheralReadyToSend(_ peripheral: PeripheralSeam) {
    guard let current = live(peripheral), current.owesCanSend else { return }
    current.owesCanSend = false
    events?.canSend(link: current.id)
  }

  private func live(_ peripheral: PeripheralSeam) -> Link? {
    guard let current = link, current.state == .up, current.peripheral.identifier == peripheral.identifier else {
      return nil
    }
    return current
  }

  private func ready(_ current: Link, _ outcome: DomainGattResult, _ error: ApplicationPortError?) {
    let inventory =
      current.inventory
      ? (current.peripheral.services ?? []).map { service in
        ApplicationGattService(
          uuid: Self.uuid(service.uuid),
          characteristics: (service.characteristics ?? []).map {
            ApplicationGattCharacteristic(uuid: Self.uuid($0.uuid), properties: Int32($0.properties.rawValue))
          })
      } : nil
    let maxWriteBytes =
      outcome == .ready ? KotlinInt(value: Int32(clamping: current.peripheral.maximumWriteWithoutResponse)) : nil
    events?.linkReady(
      link: current.id,
      result: ApplicationGattReady(outcome: outcome, maxWriteBytes: maxWriteBytes, inventory: inventory, error: error))
  }

  private func fail(_ id: Int64, _ error: ApplicationPortError) {
    post { [weak self] in self?.events?.linkFailed(link: id, error: error) }
  }

  static func radio(_ state: CBManagerState) -> ApplicationRadioState {
    switch state {
    case .poweredOn: .poweredon
    case .poweredOff: .poweredoff
    case .unauthorized: .denied
    case .unsupported: .unsupported
    case .resetting: .resetting
    default: .unknown
    }
  }

  static func refusal(_ radio: ApplicationRadioState) -> ApplicationPortErrorKind {
    switch radio {
    case .poweredoff: .poweredoff
    case .denied: .denied
    case .unsupported: .unsupported
    default: .notready
    }
  }

  // Bluetooth Core Specification Vol 3 Part B §2.5.1: 16- and 32-bit UUIDs widen with the Base UUID.
  static func uuid(_ uuid: CBUUID) -> String {
    let text = uuid.uuidString.lowercased()
    switch text.count {
    case 4: return "0000\(text)-0000-1000-8000-00805f9b34fb"
    case 8: return "\(text)-0000-1000-8000-00805f9b34fb"
    default: return text
    }
  }

  static func code(_ error: Error?) -> DomainPlatformCode? {
    guard let error else { return nil }
    let system = error as NSError
    return DomainPlatformCode(domain: system.domain, code: String(system.code))
  }
}
