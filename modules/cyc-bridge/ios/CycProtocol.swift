import Foundation

enum CycError: LocalizedError {
  case invalid(String)
  var errorDescription: String? { if case .invalid(let message) = self { return message }; return nil }
}

enum CycRequest: UInt8 {
  case identity = 111
  case selective = 50

  var frame: Data {
    let payload: [UInt8] = self == .identity ? [rawValue] : [rawValue] + withUnsafeBytes(of: CycProtocol.selectedMask.bigEndian, Array.init)
    let crc = CycProtocol.crc(payload)
    return Data([2, UInt8(payload.count)] + payload + [UInt8(crc >> 8), UInt8(crc & 255), 3])
  }
}

// Both families use the same published selective layout, verified against live X6/X12 replies.
// Keep the adapter allowlist explicit; a similar model name is not proof of compatibility.
enum CycControllerAdapter: String, Codable {
  case x6 = "X6", x12 = "X12"

  func decodeTelemetry(_ payload: [UInt8]) throws -> [String: Double] {
    switch self {
    case .x6, .x12: return try CycProtocol.decodeTelemetry(payload)
    }
  }
}

struct CycControllerIdentity: Codable {
  let controllerModel: String
  let firmwareLabel: String
  let major: UInt8
  let minor: UInt8
  let adapter: CycControllerAdapter

  var hasKnownSpeedUnit: Bool { (controllerModel == "X6" || controllerModel == "X12") && major == 5 && minor == 3 }

  func annotate(_ sample: [String: Any]) -> [String: Any] {
    var result = sample
    result["controllerModel"] = controllerModel
    result["firmwareLabel"] = firmwareLabel
    result["controllerProtocol"] = "\(major).\(minor)"
    // CYC 5.3 field 23 sends app_get_speed() in km/h, regardless of display units.
    result["controllerSpeedMps"] = hasKnownSpeedUnit ? (sample["speedRaw"] as? Double).map { $0 / 3.6 } : nil
    return result
  }
}

enum CycProtocol {
  static let selectedMask: UInt32 = 0x03c0fb8f
  static let maximumGap = 2.5
  static let columns = ["timestamp", "elapsedSeconds", "sequence", "humanPowerW", "cadenceRpm",
    "motorInputPowerW", "batteryVoltageV", "batteryCurrentA", "motorCurrentA", "motorRpm",
    "pedalTorqueNm", "controllerTempC", "motorTempC", "consumedAh", "consumedWh",
    "throttleVoltageV", "faultCode", "assistLevel", "raceMode", "speedRaw",
    "controllerSpeedMps", "controllerModel", "firmwareLabel", "controllerProtocol"]
  static let csvHeader = columns.joined(separator: ",")

  static func crc(_ bytes: [UInt8]) -> UInt16 {
    var value: UInt16 = 0
    for byte in bytes {
      value ^= UInt16(byte) << 8
      for _ in 0..<8 { value = (value & 0x8000) != 0 ? (value &<< 1) ^ 0x1021 : value &<< 1 }
    }
    return value
  }

  // Only the ASCII model/firmware prefix is a name. Real CYC responses can contain
  // opaque controller bytes before the terminating NUL; never decode/expose that tail.
  @discardableResult
  static func validateIdentity(_ payload: [UInt8]) throws -> CycControllerIdentity {
    let unsupported = CycError.invalid("Unsupported controller. Connect a CYC X6 or X12.")
    guard (4...1024).contains(payload.count), payload[0] == 111 || payload[0] == 0,
      let end = payload[3...].firstIndex(of: 0),
      end - 3 <= 128 else { throw unsupported }
    let ascii = payload[3..<end].prefix { (0x20...0x7e).contains($0) }
    guard let text = String(bytes: ascii, encoding: .ascii),
      let prefix = text.range(of: "^X(6|12)([A-Za-z_][A-Za-z0-9_]{0,29})? +[0-9]{6,8}[A-Z]{0,8}", options: .regularExpression) else { throw unsupported }
    let boundary = 3 + text.distance(from: text.startIndex, to: prefix.upperBound)
    // A partial date/model match must not turn unsupported bytes into a valid prefix.
    guard boundary == end || payload[boundary] == 0x20 else { throw unsupported }
    let parts = text[prefix].split(separator: " ")
    let model = String(parts[0])
    return CycControllerIdentity(controllerModel: model, firmwareLabel: String(parts[1]),
      major: payload[1], minor: payload[2], adapter: model.hasPrefix("X12") ? .x12 : .x6)
  }

  static func decodeTelemetry(_ payload: [UInt8]) throws -> [String: Double] {
    // Exact selected layout: 5-byte command/mask + 49 data bytes.
    guard payload.count == 54, payload.first == CycRequest.selective.rawValue else {
      throw CycError.invalid("Telemetry command or length does not match the verified profile.")
    }
    var cursor = 1
    func unsigned(_ width: Int) -> UInt32 {
      defer { cursor += width }
      return payload[cursor..<(cursor + width)].reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
    }
    guard unsigned(4) == selectedMask else { throw CycError.invalid("Unexpected telemetry field mask.") }
    func signed(_ width: Int, _ divisor: Double = 1) -> Double {
      let raw = unsigned(width)
      return (width == 2 ? Double(Int16(bitPattern: UInt16(raw))) : Double(Int32(bitPattern: raw))) / divisor
    }
    var v: [String: Double] = [:]
    v["controllerTempC"] = signed(2, 10)
    v["motorTempC"] = signed(2, 10)
    v["motorCurrentA"] = signed(4, 100)
    v["batteryCurrentA"] = signed(4, 100)
    v["motorRpm"] = signed(4)
    v["batteryVoltageV"] = signed(2, 10)
    v["consumedAh"] = signed(4, 10_000)
    v["consumedWh"] = signed(4, 10_000)
    v["cadenceRpm"] = signed(4, 10_000)
    v["throttleVoltageV"] = signed(4, 100)
    v["pedalTorqueNm"] = signed(4, 100)
    v["faultCode"] = Double(unsigned(1))
    v["humanPowerW"] = signed(4)
    v["speedRaw"] = signed(4, 100)
    v["raceMode"] = Double(unsigned(1))
    v["assistLevel"] = Double(unsigned(1))
    guard cursor == payload.count else { throw CycError.invalid("Unknown trailing telemetry data.") }
    v["motorInputPowerW"] = v["batteryVoltageV"]! * v["batteryCurrentA"]!
    return v
  }

  private static let timestampLock = NSLock()
  private static let timestampFormatter: ISO8601DateFormatter = {
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    formatter.timeZone = TimeZone(secondsFromGMT: 0)
    return formatter
  }()
  static func timestamp(_ date: Date = Date()) -> String {
    timestampLock.lock(); defer { timestampLock.unlock() }
    return timestampFormatter.string(from: date)
  }
}

struct CycFrameDecoder {
  private(set) var buffer: [UInt8] = []
  private(set) var discardedBytes = 0
  mutating func reset() { discardedBytes += buffer.count; buffer.removeAll(keepingCapacity: true) }

  mutating func feed(_ data: Data) -> [[UInt8]] {
    buffer.append(contentsOf: data)
    var packets: [[UInt8]] = []
    while !buffer.isEmpty {
      var incomplete: Int?
      var found = false
      for offset in buffer.indices {
        let start = buffer[offset]
        guard start == 2 || start == 3 else { continue }
        let header = start == 2 ? 2 : 3
        let remaining = buffer.count - offset
        guard remaining >= header else { if incomplete == nil { incomplete = offset }; continue }
        let length = buffer[(offset + 1)..<(offset + header)].reduce(0) { ($0 << 8) | Int($1) }
        guard (1...1024).contains(length), start != 3 || length > 255 else { continue }
        let size = header + length + 3
        guard remaining >= size else { if incomplete == nil { incomplete = offset }; continue }
        let payload = Array(buffer[(offset + header)..<(offset + header + length)])
        let crc = UInt16(buffer[offset + size - 3]) << 8 | UInt16(buffer[offset + size - 2])
        guard buffer[offset + size - 1] == 3, CycProtocol.crc(payload) == crc else { continue }
        packets.append(payload)
        discardedBytes += offset
        buffer.removeFirst(offset + size)
        found = true
        break
      }
      if found { continue }
      let discarded = incomplete ?? buffer.count
      discardedBytes += discarded
      buffer.removeFirst(discarded)
      break
    }
    // The only retained data is an incomplete frame (at most 1028 bytes).
    if buffer.count > 1028 { reset() }
    return packets
  }
}
