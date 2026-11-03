import Foundation
import PowerLogCore

struct WorkoutCapturePreferences: Equatable {
  let sampleHz: Double
  let distanceSource: String
  var batteryConsumptionSource: String = "controller"
}

enum WorkoutPreferences {
  private static let unreadable = WorkoutDataError.invalid(
    "Power Log couldn't read your saved settings. Open Settings on your iPhone and reset them, then start again.")

  static func read(directory: URL? = nil) throws -> WorkoutCapturePreferences {
    let root =
      try directory
      ?? FileManager.default.url(
        for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: false
      ).appendingPathComponent("power-log-preferences", isDirectory: true)
    let files: [URL]
    do {
      files = try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)
    } catch let error as CocoaError where error.code == .fileReadNoSuchFile {
      return capture(Preferences.shared.defaults())
    } catch {
      throw unreadable
    }
    // Match the immutable committed revisions written by monitor-preferences-store.native.ts.
    let revisions = files.compactMap { file -> (URL, UInt64)? in
      let name = file.lastPathComponent
      guard name.hasPrefix("monitor-"), name.hasSuffix(".json") else { return nil }
      let digits = name.dropFirst(8).dropLast(5)
      guard digits.count == 16, digits.allSatisfy({ $0 >= "0" && $0 <= "9" }),
        let revision = UInt64(digits), revision > 0, revision <= 9_007_199_254_740_991
      else { return nil }
      return (file, revision)
    }.sorted { $0.1 > $1.1 }
    for (file, _) in revisions {
      do {
        let handle = try FileHandle(forReadingFrom: file)
        defer { try? handle.close() }
        guard let data = try handle.read(upToCount: 64 * 1024 + 1), data.count <= 64 * 1024,
          let text = String(data: data, encoding: .utf8)
        else { continue }
        return capture(try Preferences.shared.decode(text: text))
      } catch {
        // Like the phone UI, recover the previous valid committed revision if the newest is unreadable.
      }
    }
    guard revisions.isEmpty else { throw unreadable }
    return capture(Preferences.shared.defaults())
  }

  private static func capture(_ preferences: MonitorPreferences) -> WorkoutCapturePreferences {
    WorkoutCapturePreferences(
      sampleHz: Double(preferences.sampleHz), distanceSource: preferences.distanceSource,
      batteryConsumptionSource: preferences.batteryConsumptionSource)
  }
}
