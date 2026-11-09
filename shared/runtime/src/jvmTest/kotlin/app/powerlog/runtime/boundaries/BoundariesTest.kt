package app.powerlog.runtime.boundaries

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoBaseDeclaration
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.declaration.KoFunctionDeclaration
import com.lemonappdev.konsist.api.declaration.KoPropertyDeclaration
import com.lemonappdev.konsist.api.provider.KoAnnotationProvider
import com.lemonappdev.konsist.api.provider.KoContainingDeclarationProvider
import com.lemonappdev.konsist.api.provider.modifier.KoVisibilityModifierProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoundariesTest {
    private val repository = File(requireNotNull(System.getProperty("powerlog.repositoryRoot"))).canonicalFile

    private val shared = production("shared")

    private val android = production("modules/power-log/android/src")

    @Test
    fun scopesAreNotEmpty() {
        assertTrue(shared.isNotEmpty(), "shared/** holds no production Kotlin")
        assertTrue(android.isNotEmpty(), "modules/power-log/android/src/** holds no production Kotlin")
    }

    @Test
    fun domainUsesNoPlatformOrRuntimeLibrary() {
        assertEquals(emptyList(), violations(module("domain")) { file -> references(file.text, domainBans) })
    }

    @Test
    fun domainAndApplicationHaveOnlyCommonMain() {
        val offending = shared.filter { file ->
            val (module, sourceSet) = location(file)
            module in setOf("domain", "application") && sourceSet != "commonMain"
        }
        assertEquals(emptyList(), offending.map(::path))
    }

    @Test
    fun coreImportsNoSlice() {
        assertEquals(
            emptyList(),
            violations(inside(shared, "app.powerlog.domain.core")) { file -> sliceReferences(file.text) },
        )
    }

    @Test
    fun slicesImportOnlyTheirEdges() {
        val found = slices.flatMap { slice ->
            val allowed = edges.getValue(slice) + slice
            violations(shared.filter { sliceOf(packageOf(it)) == slice }) { file ->
                sliceReferences(file.text).filter { it !in allowed }.map { "$slice imports $it" }
            }
        }
        assertEquals(emptyList(), found)
    }

    @Test
    fun portImportsNoApplicationSliceAndOnlyItsDomainSlices() {
        val domainSlices = setOf("recording", "settings", "health")
        assertEquals(
            emptyList(),
            violations(inside(shared, "app.powerlog.application.port")) { file ->
                references(file.text, slicePackage).mapNotNull { reference ->
                    val (layer, slice) = reference.removePrefix("app.powerlog.").split(".")
                    reference.takeIf { layer == "application" || slice !in domainSlices }
                }
            },
        )
    }

    @Test
    fun storeImportsNoApplicationSlice() {
        assertEquals(
            emptyList(),
            violations(inside(shared, "app.powerlog.application.store")) { file ->
                references(file.text, slicePackage).filter { it.startsWith("app.powerlog.application.") }
            },
        )
    }

    @Test
    fun applicationTakesTimeAndThreadsFromItsPorts() {
        assertEquals(emptyList(), violations(module("application")) { file -> references(file.text, applicationBans) })
    }

    @Test
    fun dataReachesTheApplicationOnlyThroughStore() {
        assertEquals(emptyList(), violations(module("data")) { file -> references(file.text, dataBans) })
    }

    @Test
    fun runtimePublicApiIsNotCoroutineShapedAndDoesNotThrow() {
        val found =
            module("runtime").flatMap { file ->
                val suspending = publicFunctions(file).filter { it.hasSuspendModifier }.map { "suspend ${it.name}" }
                val throwing = throwing(file).map { "@Throws $it" }
                (suspending + throwing + flowing(file).map { "Flow in $it" }).map { "${path(file)}: $it" }
            }
        assertEquals(emptyList(), found)
    }

    @Test
    fun portInterfacesAreNotCoroutineShaped() {
        val found =
            inside(shared, "app.powerlog.application.port")
                .flatMap { file -> file.interfaces(includeNested = true).map { file to it } }
                .flatMap { (file, port) ->
                    val functions =
                        port.functions(includeNested = false, includeLocal = false).filter { function ->
                            function.hasSuspendModifier || carriesFlow(function)
                        }
                    val properties =
                        port.properties(includeNested = false).filter { property ->
                            property.type?.text?.let(flow::containsMatchIn) == true
                        }
                    (functions.map { it.name } + properties.map { it.name }).map { "${path(file)}: ${port.name}.$it" }
                }
        assertEquals(emptyList(), found)
    }

    @Test
    fun androidAdaptersReachOnlyTheirPortsClockAndRideCardKit() {
        val found = androidAdapters.flatMap { folder ->
            violations(inside(android, "app.powerlog.platform.$folder")) { file ->
                references(file.text, androidAdapterBans(folder))
            }
        }
        assertEquals(emptyList(), found)
    }

    @Test
    fun webAdaptersReachOnlyPortsDomainKitAndClock() {
        val found = webAdapters.flatMap { folder ->
            val own = "app.powerlog.runtime.web.$folder"
            violations(inside(shared, own)) { file -> webOutsiders(file.text, own) }
        }
        assertEquals(emptyList(), found)
    }

    @Test
    fun patternsCatchTheirSamplesAndPassTheRest() {
        val samples =
            listOf(
                Samples(
                    "domain",
                    { references(it, domainBans) },
                    caught =
                        listOf(
                            "import kotlin.time.*",
                            "import kotlin.time.TimeSource",
                            "val now = kotlin.time.Clock.System.now()",
                            "import kotlin.jvm.*",
                            "import kotlin.jvm.JvmStatic",
                            "import kotlinx.serialization.*",
                            "import kotlinx.serialization.KSerializer",
                            "import kotlinx.serialization.json.*",
                            "import kotlin.random.*",
                            "import kotlin.uuid.Uuid",
                            "import kotlin.system.getTimeMillis",
                            "import kotlin.concurrent.Volatile",
                            "import kotlin.native.concurrent.*",
                            "import kotlin.js.JsExport",
                            "val url = \"https://powerlog.app\"; val roll = kotlin.random.Random.nextInt()",
                            "val quote = '\"'; val roll = kotlin.random.Random.nextInt(); val empty = \"\"",
                            "val text = \"\${kotlin.random.Random.nextInt()}\"",
                        ),
                    passed =
                        listOf(
                            "import kotlin.time.Duration",
                            "import kotlin.time.Duration.Companion.microseconds",
                            "import kotlin.jvm.JvmInline",
                            "import kotlinx.serialization.Serializable",
                            "import kotlinx.serialization.SerialName",
                            "import kotlinx.serialization.Transient",
                            "// import kotlin.time.*",
                            "/* kotlin.jvm.JvmStatic */",
                            "val text = \"kotlinx.serialization.*\"",
                        ),
                ),
                Samples(
                    "application",
                    { references(it, applicationBans) },
                    caught =
                        listOf(
                            "import kotlin.time.*",
                            "import kotlin.time.TimeSource",
                            "import kotlin.time.Clock",
                            "import kotlin.random.*",
                            "withContext(Dispatchers.Default) {}",
                            "GlobalScope.launch {}",
                            "runBlocking {}",
                        ),
                    passed =
                        listOf(
                            "import kotlin.time.Duration",
                            "import kotlinx.coroutines.*",
                            "import kotlinx.coroutines.CoroutineScope",
                            "// Dispatchers.Default",
                        ),
                ),
                Samples(
                    "data",
                    { references(it, dataBans) },
                    caught =
                        listOf(
                            "import app.powerlog.application.*",
                            "import app.powerlog.application.port.Clock",
                            "import app.powerlog.application.recording.*",
                            "withContext(Dispatchers.IO) {}",
                            "GlobalScope.launch {}",
                        ),
                    passed =
                        listOf(
                            "import app.powerlog.application.store.*",
                            "import app.powerlog.application.store.ArchiveWriter",
                            "import app.powerlog.domain.*",
                            "import kotlinx.coroutines.*",
                        ),
                ),
                Samples(
                    "android adapter",
                    { references(it, androidAdapterBans("ble")) },
                    caught =
                        listOf(
                            "import expo.modules.*",
                            "import expo.modules.kotlin.Promise",
                            "import app.powerlog.runtime.*",
                            "import app.powerlog.runtime.AndroidRuntime",
                            "import app.powerlog.platform.host.*",
                            "import app.powerlog.platform.host.PowerLogAndroid",
                            "import app.powerlog.kit.*",
                        ),
                    passed =
                        listOf(
                            "import app.powerlog.runtime.clock.*",
                            "import app.powerlog.runtime.clock.AndroidClock",
                            "import app.powerlog.application.port.*",
                            "import app.powerlog.domain.*",
                        ),
                ),
                Samples(
                    "android ride card",
                    { references(it, androidAdapterBans("ridecard")) },
                    caught =
                        listOf(
                            "import expo.modules.*",
                            "import app.powerlog.runtime.*",
                            "import app.powerlog.platform.host.*",
                        ),
                    passed = listOf("import app.powerlog.kit.*", "import app.powerlog.runtime.clock.AndroidClock"),
                ),
                Samples(
                    "web adapter",
                    { webOutsiders(it, "app.powerlog.runtime.web.ble") },
                    caught =
                        listOf(
                            "import app.powerlog.*",
                            "import app.powerlog.runtime.*",
                            "import app.powerlog.runtime.web.*",
                            "import app.powerlog.runtime.web.location.*",
                            "import app.powerlog.application.*",
                            "import app.powerlog.application.recording.RideSession",
                            "import app.powerlog.data.*",
                        ),
                    passed =
                        listOf(
                            "package app.powerlog.runtime.web.ble",
                            "import app.powerlog.runtime.web.ble.gatt.*",
                            "import app.powerlog.application.port.*",
                            "import app.powerlog.domain.*",
                            "import app.powerlog.kit.*",
                            "import app.powerlog.runtime.clock.WebClock",
                        ),
                ),
                Samples(
                    "slice",
                    ::sliceReferences,
                    caught =
                        listOf(
                            "import app.powerlog.domain.recording.*",
                            "import app.powerlog.application.history.HistoryPage",
                            "val units = app.powerlog.domain.settings.Units.METRIC",
                        ),
                    passed =
                        listOf(
                            "import app.powerlog.domain.core.*",
                            "import app.powerlog.domain.*",
                            "import app.powerlog.domain.recordings.Ride",
                        ),
                ),
                Samples(
                    "accessor @Throws",
                    { accessor -> throwsAnnotation.findAll(code(accessor)).map { it.value }.toList() },
                    caught =
                        listOf(
                            "@Throws(IllegalStateException::class) get() = 1",
                            "@kotlin.Throws(IllegalStateException::class) set(value) {}",
                        ),
                    passed = listOf("get() = 1", "// @Throws(IllegalStateException::class)", "@ThrowsLater get() = 1"),
                ),
                Samples(
                    "flow",
                    { type -> flow.findAll(type).map { it.value }.toList() },
                    caught = listOf("Flow<Int>", "StateFlow<RideState>", "kotlinx.coroutines.flow.SharedFlow<Unit>"),
                    passed = listOf("Overflow", "FlowRate", "Workflow", "List<Int>"),
                ),
            )
        val wrong = samples.flatMap { (rule, find, caught, passed) ->
            caught.filter { find(it).isEmpty() }.map { "$rule misses: $it" } +
                passed.filter { find(it).isNotEmpty() }.map { "$rule flags: $it" }
        }
        assertEquals(emptyList(), wrong)
    }

    private data class Samples(
        val rule: String,
        val find: (String) -> List<String>,
        val caught: List<String>,
        val passed: List<String>,
    )

    private fun production(directory: String): List<KoFileDeclaration> {
        val root = File(repository, directory)
        if (!root.isDirectory) return emptyList()
        return Konsist.scopeFromExternalDirectory(root.path).files.filter { file ->
            val relative = path(file)
            relative.endsWith(".kt") &&
                "/build/" !in relative &&
                !relative.startsWith("shared/build-logic/") &&
                location(file).second.let { sourceSet ->
                    sourceSet !in setOf("test", "androidTest", "testSupport", "fakes", "contracts") &&
                        !sourceSet.endsWith("Test")
                }
        }
    }

    private fun module(name: String) = shared.filter { location(it).first == name }

    private fun path(file: KoFileDeclaration) =
        File(file.path).canonicalFile.relativeTo(repository).invariantSeparatorsPath

    private fun location(file: KoFileDeclaration): Pair<String, String> {
        val parts = path(file).split("/")
        if (parts.take(3) == listOf("shared", "generated", "kotlin")) return "runtime" to "commonMain"
        val source = parts.indexOf("src")
        val module = if (parts.first() == "shared") parts[1] else "android"
        return module to parts.getOrElse(source + 1) { "" }
    }

    private fun packageOf(file: KoFileDeclaration) = file.packagee?.name ?: ""

    private fun inside(files: List<KoFileDeclaration>, prefix: String) = files.filter {
        packageOf(it).let { name -> name == prefix || name.startsWith("$prefix.") }
    }

    private fun publicFunctions(file: KoFileDeclaration) =
        file.functions(includeNested = true, includeLocal = false).filter(::isPublic)

    private fun publicConstructors(file: KoFileDeclaration) =
        file.classes(includeNested = true).flatMap { type ->
            type.constructors.filter(::isPublic).map { constructor ->
                "${type.name}(${constructor.parameters.joinToString { it.type.text }})" to constructor
            }
        }

    private fun publicProperties(file: KoFileDeclaration) = file.properties(includeNested = true).filter(::isPublic)

    private fun throwing(file: KoFileDeclaration) =
        publicFunctions(file).filter(::throws).map { it.name } +
            publicConstructors(file).filter { throws(it.second) }.map { it.first } +
            publicProperties(file).filter { throws(it) || accessorThrows(it) }.map { it.name }

    private fun flowing(file: KoFileDeclaration) =
        publicFunctions(file).filter(::carriesFlow).map { it.name } +
            publicConstructors(file)
                .filter { (_, constructor) -> constructor.parameters.any { flow.containsMatchIn(it.type.text) } }
                .map { it.first } +
            publicProperties(file).filter { it.type?.text?.let(flow::containsMatchIn) == true }.map { it.name }

    private fun throws(declaration: KoAnnotationProvider) = declaration.annotations.any { it.name == "Throws" }

    private fun accessorThrows(property: KoPropertyDeclaration) =
        listOfNotNull(property.getter?.text, property.setter?.text).any { throwsAnnotation.containsMatchIn(code(it)) }

    private fun carriesFlow(function: KoFunctionDeclaration) =
        (listOfNotNull(function.returnType?.text) + function.parameters.map { it.type.text }).any(flow::containsMatchIn)

    private fun sliceOf(name: String): String? {
        val parts = name.split(".")
        if (parts.size < 4 || parts[0] != "app" || parts[1] != "powerlog") return null
        return parts[3].takeIf { parts[2] in setOf("domain", "application") && it in slices }
    }

    private fun sliceReferences(text: String) = references(text, slicePackage).mapNotNull { sliceOf(it) }.distinct()

    private fun webOutsiders(text: String, own: String) =
        references(text, powerLogReference).filterNot { reference ->
            webAllowed.matches(reference) || reference == own || reference.startsWith("$own.")
        }

    private fun references(text: String, pattern: Regex): List<String> =
        pattern.findAll(code(text)).map { it.value }.distinct().toList()

    private fun code(text: String) =
        commentOrLiteral.replace(text) { token ->
            when {
                token.value.startsWith("/") -> " "
                token.value.startsWith("'") -> "' '"
                else -> template.findAll(token.value).joinToString(" ", "\"", "\"") { it.groupValues[1] }
            }
        }

    private fun violations(files: List<KoFileDeclaration>, check: (KoFileDeclaration) -> List<String>) =
        files.flatMap { file ->
            check(file).map { "${path(file)}: $it" }
        }

    private fun isPublic(declaration: KoBaseDeclaration): Boolean {
        if (declaration is KoVisibilityModifierProvider && !declaration.hasPublicOrDefaultModifier) return false
        val parent = (declaration as? KoContainingDeclarationProvider)?.containingDeclaration ?: return true
        return parent is KoFileDeclaration || isPublic(parent)
    }

    private companion object {
        val slices =
            listOf(
                "connection",
                "recording",
                "watch",
                "history",
                "charts",
                "exports",
                "imports",
                "health",
                "settings",
                "diagnostics",
            )

        val edges =
            mapOf(
                "connection" to setOf(),
                "recording" to setOf("connection", "settings"),
                "watch" to setOf("recording", "health", "settings"),
                "history" to setOf("health", "exports"),
                "charts" to setOf("recording", "connection", "imports"),
                "exports" to setOf(),
                "imports" to setOf(),
                "health" to setOf("recording"),
                "settings" to setOf(),
                "diagnostics" to setOf("connection"),
            )

        val androidAdapters =
            listOf("ble", "location", "permissions", "device", "health", "keepalive", "ridecard", "scheduler", "files")

        val webAdapters = listOf("ble", "location", "permissions", "device", "tablock", "persistence", "files")

        val flow = Regex("""\b(?:Flow|StateFlow|SharedFlow)\b""")

        val throwsAnnotation = Regex("""@(?:kotlin\.)?Throws\b""")

        val slicePackage = Regex("""\bapp\.powerlog\.(?:domain|application)\.(?:${slices.joinToString("|")})\b""")

        val domainBans =
            bans(
                packages(
                    "kotlin.random",
                    "kotlin.uuid",
                    "kotlin.system",
                    "kotlin.concurrent",
                    "kotlin.native",
                    "kotlin.js",
                ),
                namesIn("kotlin.time", "TimeSource", "Clock"),
                membersOf("kotlin.jvm", except = listOf("JvmInline")),
                membersOf("kotlinx.serialization", except = listOf("Serializable", "SerialName", "Transient")),
            )

        val applicationBans =
            bans(
                names("Dispatchers", "GlobalScope", "runBlocking"),
                packages("kotlin.random"),
                namesIn("kotlin.time", "TimeSource", "Clock"),
            )

        val dataBans =
            bans(names("Dispatchers", "GlobalScope"), membersOf("app.powerlog.application", except = listOf("store")))

        fun androidAdapterBans(folder: String) =
            bans(
                packages("expo.modules", "app.powerlog.platform.host"),
                membersOf("app.powerlog.runtime", except = listOf("clock")),
                if (folder == "ridecard") null else packages("app.powerlog.kit"),
            )

        val powerLogReference = Regex("""\bapp\.powerlog(?:\.(?:\w+|\*))+""")

        val webAllowed = Regex("""app\.powerlog\.(?:application\.port|domain|kit|runtime\.clock)\b.*""")

        val commentOrLiteral = Regex("""(?s)/\*.*?\*/|//[^\n]*|"{3}.*?"{3}|"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])+'""")

        val template = Regex("""\$\{([^{}]*)\}""")

        fun bans(vararg alternatives: String?) = Regex(alternatives.filterNotNull().joinToString("|"))

        fun packages(vararg names: String) = names.joinToString("|") { """\b${escaped(it)}\b""" }

        fun names(vararg names: String) = """\b(?:${names.joinToString("|")})\b"""

        fun namesIn(parent: String, vararg names: String) =
            """\b${escaped(parent)}\.(?:(?:${names.joinToString("|")})\b|\*)"""

        fun membersOf(parent: String, except: List<String>) =
            """\b${escaped(parent)}\.(?:(?!(?:${except.joinToString("|")})\b)\w+|\*)"""

        fun escaped(name: String) = name.replace(".", """\.""")
    }
}
