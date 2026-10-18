import Foundation

func phoneFixtureInterruption(archive: WorkoutArchive, id: String, epoch: String, timing: WorkoutOwnerTiming) throws {
  let source = try archive.metadata(id: id).watchEnabled ? "watch" : "phone"
  _ = try archive.append(
    WorkoutEvent(
      workoutId: id, kind: "lifecycle", source: source,
      timestamp: WorkoutCoding.date(timing.timestamp), elapsedSeconds: timing.elapsedSeconds,
      payload: [
        "action": .string("pause"), "interrupted": .bool(true), "clockEpoch": .string(epoch),
        "timerSeconds": .number(timing.timerSeconds),
        "controllerSequence": .string(String(try archive.sourceProgress(id: id, producer: "controller").lastSequence)),
      ],
      eventId: WorkoutStableIdentity.uuid("local-interruption:\(id):\(epoch):\(timing.elapsedSeconds)")))
}
