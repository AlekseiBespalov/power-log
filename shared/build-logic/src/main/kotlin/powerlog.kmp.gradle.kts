import com.android.build.api.dsl.LibraryExtension
import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.plugin.mpp.Executable
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.js.npm.tasks.KotlinNpmInstallTask

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

group = "app.powerlog"

version = "1.0.0"

kotlin {
    explicitApi()
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_1)
        apiVersion.set(KotlinVersion.KOTLIN_2_1)
    }
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    js {
        outputModuleName.set("power-log-${project.name}")
        nodejs { testTask { useMocha { timeout = "300s" } } }
        useEsModules()
        generateTypeScriptDefinitions()
        binaries.library()
    }
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.matching { it is TestExecutable || it is Executable }.configureEach { linkerOpts("-lsqlite3") }
    }
    sourceSets.commonMain.dependencies { implementation(catalog.findLibrary("kotlin-stdlib-common").get()) }
    sourceSets.jsMain.dependencies { implementation(catalog.findLibrary("kotlin-stdlib-js").get()) }
    sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
}

pluginManager.withPlugin("com.android.library") {
    kotlin { androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } } }
    extensions.configure<LibraryExtension> {
        namespace = "app.powerlog.${project.name}"
        compileSdk = 36
        defaultConfig { minSdk = 28 }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
}

plugins.withType<NodeJsPlugin> {
    extensions.configure<NodeJsEnvSpec> {
        download.set(false)
        command.set("node")
    }
}

if (!rootProject.extensions.extraProperties.has("powerLogNodeTasks")) {
    rootProject.extensions.extraProperties["powerLogNodeTasks"] = true
    rootProject.plugins.withType<NodeJsPlugin> {
        rootProject.extensions.configure<NodeJsEnvSpec> {
            download.set(false)
            command.set("node")
        }
    }
    rootProject.tasks.withType<KotlinNpmInstallTask>().configureEach {
        args.add("--package-lock=false")
        val packageLock = rootProject.layout.buildDirectory.file("js/node_modules/.package-lock.json")
        doLast { packageLock.get().asFile.delete() }
    }
    rootProject.tasks
        .matching { it.name == "kotlinStorePackageLock" || it.name == "kotlinUpgradePackageLock" }
        .configureEach { enabled = false }
}

val fixturePath =
    tasks.register("generateFixturePath") {
        val output = layout.buildDirectory.dir("generated/fixture-path")
        val fixtureRoot = rootDir.parentFile.resolve("tests/fixtures/contract").absolutePath
        val packageName = "app.powerlog.${project.name}"
        outputs.dir(output)
        inputs.property("fixtureRoot", fixtureRoot)
        inputs.property("packageName", packageName)
        doLast {
            output.get().file("ContractFixturePath.kt").asFile.apply {
                parentFile.mkdirs()
                writeText("package $packageName\n\ninternal const val CONTRACT_FIXTURES = \"$fixtureRoot\"\n")
            }
        }
    }

kotlin {
    sourceSets {
        if (file("src/commonTest").isDirectory) commonTest { kotlin.srcDir(fixturePath) }
        when (project.name) {
            "application" -> jvmTest { kotlin.srcDirs("src/fakes/kotlin", "src/contracts/kotlin") }
            "data",
            "runtime" ->
                commonTest { kotlin.srcDirs("../application/src/fakes/kotlin", "../application/src/contracts/kotlin") }
        }
    }
}

if (providers.gradleProperty("powerLogEveryday").isPresent) {
    val literal = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
    val pending =
        providers.fileContents(rootProject.layout.projectDirectory.file("../tests/pending.json")).asText.map { text ->
            ((JsonSlurper().parseText(text) as Map<*, *>)["kotlin"] as List<*>).map {
                val test = (it as Map<*, *>)["test"] as? String
                require(test != null && literal.matches(test)) {
                    "tests/pending.json kotlin entry \"$test\" is not a fully qualified class or class.method name"
                }
                test
            }
        }
    tasks.withType<AbstractTestTask>().configureEach {
        filter.excludeTestsMatching("*VolumeTest.*")
        pending.get().forEach(filter::excludeTestsMatching)
        filter.isFailOnNoMatchingTests = false
    }
}

tasks
    .matching { it.name in setOf("jvmTest", "macosArm64Test", "jsNodeTest", "iosSimulatorArm64Test") }
    .configureEach {
        inputs
            .dir(rootDir.resolve("../tests/fixtures/contract"))
            .withPropertyName("contractFixtures")
            .withPathSensitivity(PathSensitivity.RELATIVE)
        inputs
            .file(rootDir.resolve("../tests/fixtures/protocol.json"))
            .withPropertyName("protocolFixture")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
