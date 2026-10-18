@file:OptIn(ExperimentalJsExport::class)

package app.powerlog.core

import app.powerlog.core.telemetry.*
import kotlin.js.JsExport

@JsExport
class CoreExportColumn internal constructor(core: ExportColumnDeclaration) {
    val name: String = core.name
    val type: String = core.type
    val platforms: Array<String> = core.platforms.toTypedArray()
    val consumers: Array<String> = core.consumers.toTypedArray()
}

@JsExport
class CoreExportProjection internal constructor(core: ExportProjectionDeclaration) {
    val name: String = core.name
    val kinds: Array<String> = core.kinds.toTypedArray()
    val columns: Array<CoreExportColumn> = core.columns.map(::CoreExportColumn).toTypedArray()
}

@JsExport
class CoreExportField internal constructor(core: ExportFieldDeclaration) {
    val name: String = core.name
    val type: String = core.type
    val description: String = core.description
    val reads: Array<String> = core.reads.toTypedArray()
    val unit: String? = core.unit
    val vendor: String? = core.vendor
}

@JsExport
class CoreExportTable internal constructor(core: ExportTableDeclaration) {
    val name: String = core.name
    val path: String = core.path
    val projection: String = core.projection
    val fields: Array<CoreExportField> = core.fields.map(::CoreExportField).toTypedArray()
}

@JsExport
fun coreExportProjections(): Array<CoreExportProjection> =
    ExportCatalog.projections.map(::CoreExportProjection).toTypedArray()

@JsExport fun coreExportTables(): Array<CoreExportTable> = ExportCatalog.tables.map(::CoreExportTable).toTypedArray()

@JsExport fun coreRequiredSampleColumns(): Array<String> = StorageColumns.requiredSample.toTypedArray()

@JsExport fun coreSampleIdentityColumns(): Array<String> = StorageColumns.sampleIdentity.toTypedArray()
