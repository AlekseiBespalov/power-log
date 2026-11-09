package app.powerlog.application.store

import app.powerlog.domain.core.ids.RideId
import kotlinx.coroutines.test.TestScope

interface ArchiveHarness {
    val storesHealth: Boolean

    val queueJobs: Int

    // A fresh, empty location; the harness closes and deletes what it made when the test ends.
    suspend fun create(scope: TestScope): StoreUnderTest

    // Archives the open refuses, written under the archive's name before the first open.
    val foreignArchives: List<ForeignArchive>

    // The kinds of foreign archive the platform's storage can hold; foreignArchives covers each.
    val foreignKinds: Set<ForeignKind>
}

interface StoreUnderTest {
    val host: StoreHost
    val writer: ArchiveWriter
    val rides: RideRepository
    val measurements: MeasurementRepository
    val connection: ConnectionRepository
    val maintenance: MaintenanceRepository
    val health: HealthRepository
    val settings: SettingsRepository
    val cache: CacheRepository

    // Closes these stores and returns new, unopened ones over the same location.
    suspend fun reopen(): StoreUnderTest

    // As reopen, the way a build whose RULES_VERSION or CACHE_LAYOUT differs opens them.
    suspend fun reopenAfter(change: CacheChange): StoreUnderTest

    // Occupies the writer with a job that runs until released.
    suspend fun stallWriter(): Stall

    // Every stored row of the ride, event, session, fact and Health tables, in the units a cleanup step's maxRows
    // bounds: rows on the phones, records on web.
    suspend fun storedRows(): Long

    // Bytes or records of every file or database at the location, by name.
    suspend fun snapshot(): Map<String, ByteArray>

    suspend fun writeSettingsText(text: String?)

    // Stores, in the ride's row, a value no vocabulary of its column holds.
    suspend fun writeOutOfVocabulary(rideId: RideId)

    suspend fun corruptCachedValues(rideId: RideId)

    suspend fun failCacheWrites()
}

enum class CacheChange {
    rulesVersion,
    layout,
}

interface Stall {
    suspend fun release()
}

enum class ForeignKind {
    otherVersion,
    versionZeroWithTables,
    garbage,
}

interface ForeignArchive {
    val kind: ForeignKind

    val name: String

    suspend fun write(store: StoreUnderTest)
}

interface SqliteArchiveHarness : ArchiveHarness {
    val archiveVersion: Int

    override val foreignKinds: Set<ForeignKind>
        get() = ForeignKind.entries.toSet()

    override suspend fun create(scope: TestScope): SqliteStoreUnderTest
}

interface SqliteStoreUnderTest : StoreUnderTest {
    override suspend fun reopen(): SqliteStoreUnderTest

    suspend fun archiveFiles(): Set<String>

    suspend fun freelistCount(file: StoreFile): Long

    suspend fun fileBytes(file: StoreFile): Long

    // The file's -wal, 0 when it is missing.
    suspend fun walBytes(file: StoreFile): Long

    suspend fun writerPragmas(): List<FilePragmas>

    suspend fun injectCorruption()

    // Begins a write transaction on the archive's writer connection and keeps it open until released.
    suspend fun holdWriteTransaction(): Stall

    suspend fun statementPlans(block: suspend () -> Unit): List<StatementPlan>
}

data class StatementPlan(val sql: String, val plan: List<String>)

interface WebArchiveHarness : ArchiveHarness {
    // An IndexedDB database has no version below 1 and no bytes of its own.
    override val foreignKinds: Set<ForeignKind>
        get() = setOf(ForeignKind.otherVersion)

    override suspend fun create(scope: TestScope): WebStoreUnderTest
}

interface WebStoreUnderTest : StoreUnderTest {
    // A second, unopened connection to the same database in the same page.
    suspend fun secondConnection(): StoreUnderTest
}

interface MemoryHarness {
    fun create(): MemoryMeasurementRepository
}

interface DiagnosticLogHarness {
    val logRotateBytes: Int

    suspend fun create(scope: TestScope): LogUnderTest
}

interface LogUnderTest {
    val log: DiagnosticLogRepository

    suspend fun files(): Map<String, ByteArray>

    suspend fun writeFile(name: String, bytes: ByteArray)

    suspend fun reopen(): LogUnderTest
}
