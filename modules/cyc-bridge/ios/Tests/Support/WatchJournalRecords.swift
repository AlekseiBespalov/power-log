import Foundation

func encodedRecords(_ journal: WatchWorkoutJournal, id: String) throws -> [Data] {
  let revision = try journal.archive.metadata(id: id).collectionRevision
  var after: Int64 = 0
  var result: [Data] = []
  while true {
    let rows = try journal.archive.pageEvents(id: id, afterSequence: after, limit: 128, throughRevision: revision)
    for row in rows {
      result.append(try WorkoutCoding.encoder().encode(row.event))
      after = row.rowID
    }
    if rows.count < 128 { return result }
  }
}
