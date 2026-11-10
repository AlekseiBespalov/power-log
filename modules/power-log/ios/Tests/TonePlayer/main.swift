import Foundation

func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
  if !condition() {
    FileHandle.standardError.write(Data("HostClock: \(message)\n".utf8))
    exit(1)
  }
}

let appleSilicon = HostClock(monoUs: 5_000_000, hostTicks: 1_200_000, numer: 125, denom: 3)
expect(appleSilicon.ticks(atMonoUs: 5_000_000) == 1_200_000, "the paired instant maps to its own ticks")
expect(appleSilicon.ticks(atMonoUs: 6_000_000) == 1_200_000 + 24_000_000, "one second is 24 million 41.67 ns ticks")
expect(appleSilicon.ticks(atMonoUs: 4_000_000) == 0, "an instant before the host clock's zero clamps to zero")
expect(appleSilicon.monoUs(atTicks: 1_200_000 + 24_000_000) == 6_000_000, "ticks map back to the same instant")

let nanosecondTicks = HostClock(monoUs: 0, hostTicks: 10_000, numer: 1, denom: 1)
expect(nanosecondTicks.ticks(atMonoUs: 250) == 260_000, "nanosecond ticks scale by a thousand per microsecond")
expect(nanosecondTicks.monoUs(atTicks: 10_000 + 1_500) == 1, "partial microseconds round toward the paired instant")

print("HostClock: ok")
