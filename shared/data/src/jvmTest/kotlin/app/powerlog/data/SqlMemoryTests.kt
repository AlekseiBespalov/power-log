package app.powerlog.data

import app.powerlog.application.store.ArchiveHarness
import app.powerlog.application.store.BatchAtomicityContract
import app.powerlog.application.store.ClosedRideContract
import app.powerlog.application.store.ColumnRangeContract
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
import app.powerlog.application.store.PublishRowsContract
import app.powerlog.application.store.RememberedBikeContract
import app.powerlog.application.store.RevisionContract
import app.powerlog.application.store.RidePagesContract
import app.powerlog.application.store.SettingsContract
import app.powerlog.application.store.SqliteArchiveHarness
import app.powerlog.application.store.VocabularyContract
import app.powerlog.application.store.WriterQueueContract

private val memory = SqlArchiveHarness(JvmSqlPlatform, memory = true)

class SqlMemoryBatchAtomicityTest : BatchAtomicityContract() {
    override val harness: ArchiveHarness = memory
}

class SqlMemoryClosedRideTest : ClosedRideContract() {
    override val harness: ArchiveHarness = memory
}

class SqlMemoryColumnRangeTest : ColumnRangeContract() {
    override val harness: ArchiveHarness = memory
}

class SqlMemoryEndCheckpointTest : EndCheckpointContract() {
    override val harness: ArchiveHarness = memory
}

class SqlMemoryHealthColumnRangeTest : HealthColumnRangeContract() {
    override val harness: SqliteArchiveHarness = memory
}

class SqlMemoryHealthLastFactTest : HealthLastFactContract() {
    override val harness: SqliteArchiveHarness = memory
}

class SqlMemoryHealthNearestTest : HealthNearestContract() {
    override val harness: SqliteArchiveHarness = memory
}

class SqlMemoryHealthPagingTest : HealthPagingContract() {
    override val harness: SqliteArchiveHarness = memory
}

class SqlMemoryHealthSampleRepeatsTest : HealthSampleRepeatsContract() {
    override val harness: SqliteArchiveHarness = memory
}

class SqlMemoryHealthWorkTest : HealthWorkContract() {
    override val harness: SqliteArchiveHarness = memory
}

class SqlMemoryImpliedExistenceTest : ImpliedExistenceContract() {
    override val harness: ArchiveHarness = memory
}

class SqlMemoryKeysetPagingTest : KeysetPagingContract() {
    override val harness: ArchiveHarness = memory
}

class SqlMemoryLastFactTest : LastFactContract() {
    override val harness: ArchiveHarness = memory
}

class SqlMemoryMeasurementReadsTest : MeasurementReadsContract() {
    override val harness: ArchiveHarness = memory
}

class SqlMemoryNearestTest : NearestContract() {
    override val harness: ArchiveHarness = memory
}

class SqlMemoryPublishRowsTest : PublishRowsContract() {
    override val harness: SqliteArchiveHarness = memory
}

class SqlMemoryRememberedBikeTest : RememberedBikeContract() {
    override val harness: ArchiveHarness = memory
}

class SqlMemoryRevisionTest : RevisionContract() {
    override val harness: ArchiveHarness = memory
}

class SqlMemoryRidePagesTest : RidePagesContract() {
    override val harness: ArchiveHarness = memory
}

class SqlMemorySettingsTest : SettingsContract() {
    override val harness: ArchiveHarness = memory
}

class SqlMemoryVocabularyTest : VocabularyContract() {
    override val harness: ArchiveHarness = memory
}

class SqlMemoryWriterQueueTest : WriterQueueContract() {
    override val harness: ArchiveHarness = memory
}
