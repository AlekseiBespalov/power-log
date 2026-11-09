package app.powerlog.application.api.generator

import app.powerlog.application.api.ApiStream
import app.powerlog.application.api.Empty
import app.powerlog.application.api.Outcome
import app.powerlog.application.exports.ExportColumn
import app.powerlog.application.exports.ExportPage
import java.nio.file.Files
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.serialization.Serializable

class GenerateApiTypesTest {
    @Test
    fun exportPageColumnsAreWrittenAsExportColumns() {
        val types = TypeScriptTypes()
        types.reference(typeOf<ExportPage>())
        assertEquals("export type ExportColumn = Uint8Array | (string | null)[];", types.declarations["ExportColumn"])
        assertContains(types.declarations.getValue("ExportPage"), "columns: Record<string, ExportColumn>;")
    }

    @Test
    fun exportColumnsOutsideExportPageFailGeneration() {
        val failure = assertFailsWith<IllegalStateException> { TypeScriptTypes().reference(typeOf<StrayColumns>()) }
        assertEquals("ExportColumn crosses only as the values of ExportPage.columns", failure.message)
    }

    @Test
    fun aSectionRootGeneratesItsOwnPrefixedTypesAndDispatch() {
        val output = Files.createTempDirectory("api-section").toFile()
        try {
            main(arrayOf(output.path, SampleApi::class.java.name, "sample"))
            val types = output.resolve("ts/sample.ts").readText()
            assertContains(types, "'sample.touch': { input: Empty; output: null };")
            assertContains(types, "'sample.things.read': { input: SampleInput; output: SampleView };")
            assertContains(types, "'sample.things.feed': { params: Empty; value: SampleView };")
            assertContains(types, "export type QueryName = 'sample.things.read';")
            assertContains(types, "export const SAMPLE_HASH = '")
            assertFalse("ExportPageCall" in types || "FailureCode" in types || "apiVersion" in types, types)
            val dispatch = output.resolve("kotlin/app/powerlog/runtime/bridge/SampleDispatch.kt").readText()
            assertContains(
                dispatch,
                "internal class SampleDispatch(private val api: SampleApi, private val codec: WireCodec) : ApiSection {",
            )
            assertContains(dispatch, "setOf(\n            \"sample\",\n        )")
            assertContains(
                dispatch,
                "api.things.read(codec.decode(GenerateApiTypesTest.SampleInput.serializer(), input))",
            )
            assertContains(dispatch, "api.things.feed.next(codec.decode(Empty.serializer(), params), after, key)")
            assertFalse("exportPage" in dispatch, dispatch)
        } finally {
            output.deleteRecursively()
        }
    }

    @Serializable class StrayColumns(val columns: Map<String, ExportColumn>)

    interface SampleApi {
        val things: SampleThings

        suspend fun touch(input: Empty): Outcome<Unit>
    }

    interface SampleThings {
        val feed: ApiStream<Empty, SampleView>

        suspend fun read(input: SampleInput): Outcome<SampleView>
    }

    @Serializable class SampleInput(val key: String)

    @Serializable class SampleView(val count: Int)
}
