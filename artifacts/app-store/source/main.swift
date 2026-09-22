import Foundation

guard CommandLine.arguments.count == 2 else { fatalError("Usage: examples /new/output/PowerLog") }
let root = URL(fileURLWithPath: CommandLine.arguments[1])
guard !FileManager.default.fileExists(atPath: root.path) else { fatalError("Output must be a new directory") }
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"))
let reports = try WorkoutExampleRides.write(archive: archive)
try JSONSerialization.data(withJSONObject: reports, options: [.prettyPrinted, .sortedKeys]).write(to: root.deletingLastPathComponent().appendingPathComponent("example-values.json"))
print("Generated \(reports.count) fictional, internally consistent rides using the production writer.")
