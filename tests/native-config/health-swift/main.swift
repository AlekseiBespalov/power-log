import Foundation
import PowerLogCore

var checks = 0
func rejects(_ work: () throws -> Void) {
  do {
    try work()
    fatalError("Invalid Health value was admitted")
  } catch {
    precondition((error as NSError).domain == "KotlinException")
    checks += 1
  }
}

let ride = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
let endpoint = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
let origin = Origin(
  provider: .applehealth, dataOrigin: "app.synthetic", providerObservationId: nil,
  originEventId: ride, producer: .phone, sequence: 9_007_199_254_740_993, originRevision: nil)
let encoded = try HealthCodec.shared.encodeOrigin(value: origin)
let decoded = try HealthCodec.shared.decodeOrigin(text: encoded)
precondition(decoded.sequence == 9_007_199_254_740_993)
let reencoded = try HealthCodec.shared.encodeOrigin(value: decoded)
precondition(reencoded == encoded)
checks += 2
let kind = HealthKind.riderpower
let second = try HealthIds.shared.second(ride: ride, kind: kind, n: 0)
precondition(second == "powerlog:\(ride):rider-power:second:0")
checks += 1
rejects {
  _ = try HealthCodec.shared.decodeOrigin(text: encoded.replacingOccurrences(of: "phone", with: "unknown"))
}
let metadata = PublicationMetadata(
  rideId: ride, recordingOwner: .phone, ownerEndpointId: endpoint,
  healthPublisher: .phone, publisherEndpointId: endpoint, indoor: true, recordGPS: false)
let source = MeasurementSource(source: .controller, device: .controller, provider: nil, dataOrigin: nil)
let quantity = ProviderContent.Quantity(
  fields: QuantityContent(
    kind: kind, startUs: 0, endUs: 1_000_000,
    value: .nan, unit: "W", source: source, metadata: metadata))
let content = NormalizedContent.Present(fields: quantity, projection: AdapterProjection.Apple(content: quantity))
rejects { _ = try HealthCodec.shared.contentDigest(content: content) }
rejects { _ = try SecondBuckets.shared.floorDiv(value: 0, divisor: 0) }
rejects { _ = try SecondKernel.shared.policy(kind: HealthKind.cyclingdistance) }
rejects { _ = try MeasurementSource(source: "invalid", device: "controller", provider: nil, dataOrigin_: nil) }
let validQuantity = ProviderContent.Quantity(
  fields: QuantityContent(
    kind: kind, startUs: 0, endUs: 1_000_000,
    value: 150, unit: "W", source: source, metadata: metadata))
let validContent = NormalizedContent.Present(
  fields: validQuantity, projection: AdapterProjection.Apple(content: validQuantity))
let plan = RecordPlan(
  syncId: second, component: .riderpower, operation: .upsert, content: validContent, sourceRevision: 1, routeIntent: nil
)
let stored = try Publication.shared.plan(plan: plan)
precondition(stored.version == 1 && stored.syncId == second && stored.state == .notsubmitted)
let repeated = try Publication.shared.plan(plan: plan)
precondition(repeated.contentDigest == stored.contentDigest)
checks += 2
let energy = EnergyInterval(
  source: .phone, origin: origin, observationRevision: 1, startUs: 0, endUs: 1_000_000,
  originalStartTimestamp: "2026-01-01T00:00:00Z", originalEndTimestamp: "2026-01-01T00:00:01Z", valueKcal: 2)
let window = AnalysisRange(startUs: 0, endUs: 1_000_000)
let selected = try HealthOriginSelector.shared.energy(
  records: [energy], source: "phone", metric: "active", window: window, revision: 1)
precondition(selected?.dataOrigin == "app.synthetic" && selected?.unionCoverageUs == 1_000_000)
let coverage = EnergyOriginPreparation(source: "phone", window: window)
try coverage.append(metric: "active", record: energy)
precondition(coverage.selected()?.dataOrigin == "app.synthetic")
let resting = EnergyReducer(metric: "basal", endUs: 1_000_000, selectedSource: "phone", selectedOrigin: "app.synthetic")
resting.append(evidence: EnergyEvidence.IntervalRecord(metric: "basal", interval: energy))
precondition(resting.valueQ()?.int64Value == 2_000_000)
checks += 3
print("Health Swift framework: \(checks) checks passed")
