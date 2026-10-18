import Foundation
#if os(iOS)
  import ActivityKit
#endif

struct PowerLogRideAttributes {
  struct Entry: Codable, Hashable, Sendable {
    let text: String
    let unit: String
    let color: String
    let accessibility: String
  }
  struct Entries: Codable, Hashable, Sendable {
    let available: Entry
    let expired: Entry
  }
  struct Status: Codable, Hashable, Sendable {
    let text: String
    let symbol: String
    let accessibility: String
  }
  struct Timer: Codable, Hashable, Sendable {
    let mode: String
    let origin: Date?
    let text: String?
    let accessibility: String
  }
  struct Control: Codable, Hashable, Sendable {
    let action: String
    let label: String
    let symbol: String
    let rideID: String
    let controlToken: String
    let expectedPhase: String
  }
  struct Render: Codable, Hashable, Sendable {
    let bike: Entry
    let heartRate: Entry
    let status: Status
    let timer: Timer
    let controls: [Control]
    let showReadings: Bool
  }
  struct ContentState: Codable, Hashable, Sendable {
    let rideID: String
    let publicationSequence: Int64
    let controlToken: String
    let expectedPhase: String
    let bike: Entries
    let heartRate: Entries
    let bikeExpiresAt: Date?
    let heartRateExpiresAt: Date?
    let bikeAvailableStatus: Status
    let bikeExpiredStatus: Status
    let controls: [Control]
    let timer: Timer
    let systemStale: Render
    let showReadings: Bool
    let staleDate: Date?

    func render(at date: Date, isStale: Bool) -> Render {
      if isStale { return systemStale }
      let bikeAvailable = bikeExpiresAt.map { date < $0 } ?? false
      let heartAvailable = heartRateExpiresAt.map { date < $0 } ?? false
      return Render(
        bike: bikeAvailable ? bike.available : bike.expired,
        heartRate: heartAvailable ? heartRate.available : heartRate.expired,
        status: bikeAvailable ? bikeAvailableStatus : bikeExpiredStatus,
        timer: timer, controls: controls, showReadings: showReadings)
    }
  }
  let rideID: String
}

#if os(iOS)
  extension PowerLogRideAttributes: ActivityAttributes {}
#endif
