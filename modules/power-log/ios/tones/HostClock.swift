import Foundation

struct HostClock {
  let monoUs: Int64
  let hostTicks: UInt64
  let numer: UInt32
  let denom: UInt32

  func ticks(atMonoUs: Int64) -> UInt64 {
    let deltaTicks = (atMonoUs - monoUs) * 1_000 * Int64(denom) / Int64(numer)
    return UInt64(max(0, Int64(hostTicks) + deltaTicks))
  }

  func monoUs(atTicks: UInt64) -> Int64 {
    monoUs + (Int64(atTicks) - Int64(hostTicks)) * Int64(numer) / Int64(denom) / 1_000
  }
}
