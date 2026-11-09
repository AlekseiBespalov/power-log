package app.powerlog.data

import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.runner.RunWith

private val files = SqlArchiveHarness(AndroidSqlPlatform)

@RunWith(AndroidJUnit4::class)
class SqlFileBatchAtomicityTest : BatchAtomicityContract() {
    override val harness: ArchiveHarness = files
}

@RunWith(AndroidJUnit4::class)
class SqlFileCacheTest : CacheContract() {
    override val harness: ArchiveHarness = files
}

@RunWith(AndroidJUnit4::class)
class SqlFileCapturePlanTest : CapturePlanContract() {
    override val harness: SqliteArchiveHarness = files
}

@RunWith(AndroidJUnit4::class)
class SqlFileClosedRideTest : ClosedRideContract() {
    override val harness: ArchiveHarness = files
}

@RunWith(AndroidJUnit4::class)
class SqlFileColumnRangeTest : ColumnRangeContract() {
    override val harness: ArchiveHarness = files
}

@RunWith(AndroidJUnit4::class)
class SqlFileCorruptArchiveTest : CorruptArchiveContract() {
    override val harness: SqliteArchiveHarness = files
}

@RunWith(AndroidJUnit4::class)
class SqlFileDeletionTest : DeletionContract() {
    override val harness: ArchiveHarness = files
}

@RunWith(AndroidJUnit4::class)
class SqlFileDurabilityTest : DurabilityContract() {
    override val harness: ArchiveHarness = files
}

@RunWith(AndroidJUnit4::class)
class SqlFileEndCheckpointTest : EndCheckpointContract() {
    override val harness: ArchiveHarness = files
}

@RunWith(AndroidJUnit4::class)
class SqlFileHealthColumnRangeTest : HealthColumnRangeContract() {
    override val harness: SqliteArchiveHarness = files
}

@RunWith(AndroidJUnit4::class)
class SqlFileHealthLastFactTest : HealthLastFactContract() {
    override val harness: SqliteArchiveHarness = files
}

@RunWith(AndroidJUnit4::class)
class SqlFileHealthNearestTest : HealthNearestContract() {
    override val harness: SqliteArchiveHarness = files
}

@RunWith(AndroidJUnit4::class)
class SqlFileHealthPagingTest : HealthPagingContract() {
    override val harness: SqliteArchiveHarness = files
}

@RunWith(AndroidJUnit4::class)
class SqlFileHealthSampleRepeatsTest : HealthSampleRepeatsContract() {
    override val harness: SqliteArchiveHarness = files
}

@RunWith(AndroidJUnit4::class)
class SqlFileHealthWorkTest : HealthWorkContract() {
    override val harness: SqliteArchiveHarness = files
}

@RunWith(AndroidJUnit4::class)
class SqlFileImpliedExistenceTest : ImpliedExistenceContract() {
    override val harness: ArchiveHarness = files
}

@RunWith(AndroidJUnit4::class)
class SqlFileKeysetPagingTest : KeysetPagingContract() {
    override val harness: ArchiveHarness = files
}

@RunWith(AndroidJUnit4::class)
class SqlFileLastFactTest : LastFactContract() {
    override val harness: ArchiveHarness = files
}

@RunWith(AndroidJUnit4::class)
class SqlFileMeasurementReadsTest : MeasurementReadsContract() {
    override val harness: ArchiveHarness = files
}

@RunWith(AndroidJUnit4::class)
class SqlFileNearestTest : NearestContract() {
    override val harness: ArchiveHarness = files
}

@RunWith(AndroidJUnit4::class)
class SqlFilePragmaTest : PragmaContract() {
    override val harness: SqliteArchiveHarness = files
}

@RunWith(AndroidJUnit4::class)
class SqlFilePublishRowsTest : PublishRowsContract() {
    override val harness: SqliteArchiveHarness = files
}

@RunWith(AndroidJUnit4::class)
class SqlFileReadersDuringWriteTest : ReadersDuringWriteContract() {
    override val harness: SqliteArchiveHarness = files
}

@RunWith(AndroidJUnit4::class)
class SqlFileReclaimTest : ReclaimContract() {
    override val harness: SqliteArchiveHarness = files
}

@RunWith(AndroidJUnit4::class)
class SqlFileRefusedArchiveTest : RefusedArchiveContract() {
    override val harness: ArchiveHarness = files
}

@RunWith(AndroidJUnit4::class)
class SqlFileRememberedBikeTest : RememberedBikeContract() {
    override val harness: ArchiveHarness = files
}

@RunWith(AndroidJUnit4::class)
class SqlFileRevisionTest : RevisionContract() {
    override val harness: ArchiveHarness = files
}

@RunWith(AndroidJUnit4::class)
class SqlFileRidePagesTest : RidePagesContract() {
    override val harness: ArchiveHarness = files
}

@RunWith(AndroidJUnit4::class)
class SqlFileSettingsTest : SettingsContract() {
    override val harness: ArchiveHarness = files
}

@RunWith(AndroidJUnit4::class)
class SqlFileVocabularyTest : VocabularyContract() {
    override val harness: ArchiveHarness = files
}

@RunWith(AndroidJUnit4::class)
class SqlFileWriterQueueTest : WriterQueueContract() {
    override val harness: ArchiveHarness = files
}

@RunWith(AndroidJUnit4::class)
class DiagnosticLogFilesTest : DiagnosticLogContract() {
    override val harness: DiagnosticLogHarness = LogFilesHarness(AndroidSqlPlatform) { JavaLogFiles(File(it)) }
}
