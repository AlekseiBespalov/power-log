package app.powerlog.data

import app.powerlog.application.store.ImportedRideContract
import app.powerlog.application.store.MemoryHarness
import app.powerlog.application.store.MemoryMeasurementRepository
import app.powerlog.application.store.PreviewContract

private val memory =
    object : MemoryHarness {
        override fun create(): MemoryMeasurementRepository = MemoryMeasurements()
    }

class MemoryPreviewTest : PreviewContract() {
    override val harness: MemoryHarness = memory
}

class MemoryImportedRideTest : ImportedRideContract() {
    override val harness: MemoryHarness = memory
}
