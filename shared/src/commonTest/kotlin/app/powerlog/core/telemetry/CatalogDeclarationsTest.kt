package app.powerlog.core.telemetry

import app.powerlog.core.analytics.fixture
import kotlin.test.*

class CatalogDeclarationsTest {
    @Test
    fun physicalColumnsAndSampleShapeRetainTheirExistingOrder() {
        val expected = fixture("storage/catalog-columns.json")
        for ((name, columns) in
            listOf(
                "telemetry" to StorageColumns.telemetry,
                "appleLocation" to StorageColumns.appleLocation,
                "androidLocation" to StorageColumns.androidLocation,
                "health" to StorageColumns.health,
                "requiredSample" to StorageColumns.requiredSample,
                "sampleIdentity" to StorageColumns.sampleIdentity,
            )) assertEquals(expected[name].list().map { it.text() }, columns, name)
        val bits = expected["androidMetricBits"].list().map { it.text() }
        assertEquals(bits, StorageColumns.androidMetricBits.keys.toList())
        assertEquals(bits.indices.toList(), StorageColumns.androidMetricBits.values.toList())
        val channels =
            ExportCatalog.tables
                .first { it.name == "telemetry" }
                .fields
                .filter { it.reads.size == 1 && it.reads[0] in StorageColumns.telemetry }
                .map { it.reads[0] }
        assertEquals(StorageColumns.telemetry.toSet(), channels.toSet())
    }

    @Test
    fun derivedZipKeepsTheSameControllerContinuityEvidenceAsFit() {
        val continuity =
            setOf(
                "elapsedSeconds",
                "producer",
                "producerSequence",
                "clockEpoch",
                "connection",
                "active",
                "interval",
                "action",
                "interrupted",
                "controllerSequence",
            )
        for (name in listOf("telemetry", "lifecycle")) {
            val projection = ExportCatalog.projections.first { it.name == name }
            for (platform in listOf("ios", "android", "web")) {
                val fit = projection.columnsFor(platform, "fit").filter { it.name in continuity }.map { it.name }
                val zip = projection.columnsFor(platform, "zip").filter { it.name in continuity }.map { it.name }
                assertEquals(fit, zip, "$name:$platform")
            }
        }
    }

    @Test
    fun healthProvenanceIsAvailableOnBothPhonesWithOneOriginAndMemberIdentity() {
        val projection = ExportCatalog.projections.first { it.name == "healthZip" }
        val names =
            listOf(
                "provider",
                "providerParentId",
                "memberIndex",
                "dataOrigin",
                "originalEventId",
                "originalEndTimestamp",
                "logicalKey",
                "targetEventId",
                "originRevision",
                "valueBits",
                "evidence",
            )
        for (platform in listOf("ios", "android")) {
            val columns = projection.columnsFor(platform, "zip")
            for (name in names) assertEquals("string", columns.first { it.name == name }.type)
            assertFalse(columns.any { it.name in listOf("sampleUUID", "sourceBundleIdentifier") })
        }
        assertFalse(
            ExportCatalog.tables.first { it.name == "health" }.fields.any { it.name in listOf("sampleId", "sourceApp") }
        )
    }

    @Test
    fun csvFieldsReadDeclaredZipColumnsAndProjectionKeysHaveOneType() {
        val types = mutableMapOf<String, String>()
        for (projection in ExportCatalog.projections) {
            for (column in projection.columns) assertEquals(types.getOrPut(column.name) { column.type }, column.type)
        }
        for (table in ExportCatalog.tables) {
            val projection = ExportCatalog.projections.first { it.name == table.projection }
            val columns = projection.columns.filter { "zip" in it.consumers }.map { it.name }.toSet()
            for (field in table.fields) assertTrue(field.reads.all { it in columns }, "${table.name}.${field.name}")
        }
    }
}
