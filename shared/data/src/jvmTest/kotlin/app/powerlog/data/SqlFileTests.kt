package app.powerlog.data

import app.powerlog.application.store.ArchiveHarness
import app.powerlog.application.store.BatchAtomicityContract
import app.powerlog.application.store.CacheContract
import app.powerlog.application.store.CapturePlanContract
import app.powerlog.application.store.ClosedRideContract
import app.powerlog.application.store.ColumnRangeContract
import app.powerlog.application.store.CorruptArchiveContract
import app.powerlog.application.store.DeletionContract
import app.powerlog.application.store.DiagnosticLogContract
import app.powerlog.application.store.DiagnosticLogHarness
import app.powerlog.application.store.DurabilityContract
import app.powerlog.application.store.EndCheckpointContract
import app.powerlog.application.store.HealthColumnRangeContract
import app.powerlog.application.store.HealthLastFactContract
import app.powerlog.application.store.HealthNearestContract
import app.powerlog.application.store.HealthPagingContract
import app.powerlog.application.store.HealthSampleRepeatsContract
import app.powerlog.application.store.HealthWorkContract
import app.powerlog.application.store.ImpliedExistenceContract
import app.powerlog.application.store.KeysetPagingContract
import app.powerlog.application.store.LastFactContract
import app.powerlog.application.store.MeasurementReadsContract
import app.powerlog.application.store.NearestContract
import app.powerlog.application.store.PragmaContract
import app.powerlog.application.store.PublishRowsContract
import app.powerlog.application.store.ReadersDuringWriteContract
import app.powerlog.application.store.ReclaimContract
import app.powerlog.application.store.RefusedArchiveContract
import app.powerlog.application.store.RememberedBikeContract
import app.powerlog.application.store.RevisionContract
import app.powerlog.application.store.RidePagesContract
import app.powerlog.application.store.SettingsContract
import app.powerlog.application.store.SqliteArchiveHarness
import app.powerlog.application.store.VocabularyContract
import app.powerlog.application.store.WriterQueueContract
import java.io.File

private val files = SqlArchiveHarness(JvmSqlPlatform)

class SqlFileBatchAtomicityTest : BatchAtomicityContract() {
    override val harness: ArchiveHarness = files
}

class SqlFileCacheTest : CacheContract() {
    override val harness: ArchiveHarness = files
}

class SqlFileCapturePlanTest : CapturePlanContract() {
    override val harness: SqliteArchiveHarness = files
}

class SqlFileClosedRideTest : ClosedRideContract() {
    override val harness: ArchiveHarness = files
}

class SqlFileColumnRangeTest : ColumnRangeContract() {
    override val harness: ArchiveHarness = files
}

class SqlFileCorruptArchiveTest : CorruptArchiveContract() {
    override val harness: SqliteArchiveHarness = files
}

class SqlFileDeletionTest : DeletionContract() {
    override val harness: ArchiveHarness = files
}

class SqlFileDurabilityTest : DurabilityContract() {
    override val harness: ArchiveHarness = files
}

class SqlFileEndCheckpointTest : EndCheckpointContract() {
    override val harness: ArchiveHarness = files
}

class SqlFileHealthColumnRangeTest : HealthColumnRangeContract() {
    override val harness: SqliteArchiveHarness = files
}

class SqlFileHealthLastFactTest : HealthLastFactContract() {
    override val harness: SqliteArchiveHarness = files
}

class SqlFileHealthNearestTest : HealthNearestContract() {
    override val harness: SqliteArchiveHarness = files
}

class SqlFileHealthPagingTest : HealthPagingContract() {
    override val harness: SqliteArchiveHarness = files
}

class SqlFileHealthSampleRepeatsTest : HealthSampleRepeatsContract() {
    override val harness: SqliteArchiveHarness = files
}

class SqlFileHealthWorkTest : HealthWorkContract() {
    override val harness: SqliteArchiveHarness = files
}

class SqlFileImpliedExistenceTest : ImpliedExistenceContract() {
    override val harness: ArchiveHarness = files
}

class SqlFileKeysetPagingTest : KeysetPagingContract() {
    override val harness: ArchiveHarness = files
}

class SqlFileLastFactTest : LastFactContract() {
    override val harness: ArchiveHarness = files
}

class SqlFileMeasurementReadsTest : MeasurementReadsContract() {
    override val harness: ArchiveHarness = files
}

class SqlFileNearestTest : NearestContract() {
    override val harness: ArchiveHarness = files
}

class SqlFilePragmaTest : PragmaContract() {
    override val harness: SqliteArchiveHarness = files
}

class SqlFilePublishRowsTest : PublishRowsContract() {
    override val harness: SqliteArchiveHarness = files
}

class SqlFileReadersDuringWriteTest : ReadersDuringWriteContract() {
    override val harness: SqliteArchiveHarness = files
}

class SqlFileReclaimTest : ReclaimContract() {
    override val harness: SqliteArchiveHarness = files
}

class SqlFileRefusedArchiveTest : RefusedArchiveContract() {
    override val harness: ArchiveHarness = files
}

class SqlFileRememberedBikeTest : RememberedBikeContract() {
    override val harness: ArchiveHarness = files
}

class SqlFileRevisionTest : RevisionContract() {
    override val harness: ArchiveHarness = files
}

class SqlFileRidePagesTest : RidePagesContract() {
    override val harness: ArchiveHarness = files
}

class SqlFileSettingsTest : SettingsContract() {
    override val harness: ArchiveHarness = files
}

class SqlFileVocabularyTest : VocabularyContract() {
    override val harness: ArchiveHarness = files
}

class SqlFileWriterQueueTest : WriterQueueContract() {
    override val harness: ArchiveHarness = files
}

class DiagnosticLogFilesTest : DiagnosticLogContract() {
    override val harness: DiagnosticLogHarness = LogFilesHarness(JvmSqlPlatform) { JavaLogFiles(File(it)) }
}
