import Foundation

public protocol CBCentralManagerDelegate: AnyObject {}
public protocol CBPeripheralDelegate: AnyObject {}
public enum CBManagerState { case unknown, resetting, unsupported, unauthorized, poweredOff, poweredOn }
public enum CBPeripheralState { case disconnected, connecting, connected, disconnecting }
public enum CBCharacteristicWriteType { case withoutResponse }

public let CBCentralManagerOptionRestoreIdentifierKey = "restore"
public let CBCentralManagerOptionShowPowerAlertKey = "alert"
public let CBCentralManagerScanOptionAllowDuplicatesKey = "duplicates"
public let CBConnectPeripheralOptionEnableAutoReconnect = "reconnect"
public let CBAdvertisementDataLocalNameKey = "name"
public let CBAdvertisementDataServiceUUIDsKey = "services"
public let CBAdvertisementDataOverflowServiceUUIDsKey = "overflow"
public let CBAdvertisementDataManufacturerDataKey = "manufacturer"
public let CBCentralManagerRestoredStatePeripheralsKey = "peripherals"

public final class CBUUID: NSObject {
  private let value: String
  public init(string: String) { value = string }
  public var uuidString: String { value }
  public override func isEqual(_ object: Any?) -> Bool { (object as? CBUUID)?.value == value }
  public override var hash: Int { value.hashValue }
}

public struct CBCharacteristicProperties: OptionSet {
  public let rawValue: Int
  public init(rawValue: Int) { self.rawValue = rawValue }
  public static let writeWithoutResponse = Self(rawValue: 1)
  public static let notify = Self(rawValue: 2)
}

public final class CBCharacteristic {
  public let uuid: CBUUID
  public let properties: CBCharacteristicProperties
  public var value: Data?
  public var isNotifying = true
  public init(uuid: CBUUID, properties: CBCharacteristicProperties) {
    self.uuid = uuid
    self.properties = properties
  }
}

public final class CBService {
  public let uuid: CBUUID
  public var characteristics: [CBCharacteristic]?
  public init(uuid: CBUUID, characteristics: [CBCharacteristic]) {
    self.uuid = uuid
    self.characteristics = characteristics
  }
}

public final class CBPeripheral {
  public let identifier = UUID()
  public var name: String? = "Synthetic CYC"
  public weak var delegate: CBPeripheralDelegate?
  public var state: CBPeripheralState = .disconnected
  public var services: [CBService]?
  public var canSendWriteWithoutResponse = true
  public var maximumWriteLength = 64
  public var onWrite: ((Data) -> Void)?
  public init() {}
  public func maximumWriteValueLength(for type: CBCharacteristicWriteType) -> Int { maximumWriteLength }
  public func writeValue(_ data: Data, for characteristic: CBCharacteristic, type: CBCharacteristicWriteType) {
    onWrite?(data)
  }
  public func discoverServices(_ services: [CBUUID]?) {}
  public func discoverCharacteristics(_ characteristics: [CBUUID]?, for service: CBService) {}
  public func setNotifyValue(_ enabled: Bool, for characteristic: CBCharacteristic) {}
}

public final class CBCentralManager {
  public static var latest: CBCentralManager!
  public static var peripherals: [CBPeripheral] = []
  public var state: CBManagerState = .poweredOn
  public private(set) var cancellations = 0
  public private(set) var connections = 0
  public init(delegate: CBCentralManagerDelegate?, queue: DispatchQueue?, options: [String: Any]?) {
    Self.latest = self
  }
  public func scanForPeripherals(withServices services: [CBUUID]?, options: [String: Any]?) {}
  public func stopScan() {}
  public func retrievePeripherals(withIdentifiers identifiers: [UUID]) -> [CBPeripheral] {
    Self.peripherals.filter { identifiers.contains($0.identifier) }
  }
  public func connect(_ peripheral: CBPeripheral, options: [String: Any]?) {
    connections += 1
    peripheral.state = .connecting
  }
  public func cancelPeripheralConnection(_ peripheral: CBPeripheral) {
    cancellations += 1
    peripheral.state = .disconnecting
  }
}
