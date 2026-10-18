import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.npm.tasks.KotlinNpmInstallTask

plugins { kotlin("multiplatform") version "2.4.20" }

group = "app.powerlog"

version = "0.3.0"

kotlin {
    // The Android app compiles with Kotlin 2.1.20 (app.config.ts), which must read this library's metadata.
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_1)
        apiVersion.set(KotlinVersion.KOTLIN_2_1)
    }
    val apple = XCFramework("PowerLogCore")
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "PowerLogCore"
            isStatic = true
            apple.add(this)
        }
    }
    macosArm64 {
        binaries.framework {
            baseName = "PowerLogCore"
            isStatic = true
            apple.add(this)
        }
    }
    jvm()
    js {
        nodejs { testTask { useMocha { timeout = "300s" } } }
        useEsModules()
        generateTypeScriptDefinitions()
        binaries.library()
    }
    sourceSets.commonMain.dependencies { implementation(kotlin("stdlib", "2.1.20")) }
    sourceSets.jsMain.dependencies { implementation(kotlin("stdlib-js", "2.4.20")) }
    sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
    sourceSets.commonTest { kotlin.srcDir(layout.buildDirectory.dir("generated/fixture-path")) }
}

rootProject.extensions.configure<NodeJsEnvSpec> {
    download.set(false)
    command.set("node")
}

tasks.withType<KotlinNpmInstallTask>().configureEach {
    args.add("--package-lock=false")
    val packageLock = rootProject.layout.buildDirectory.file("js/node_modules/.package-lock.json")
    doLast { packageLock.get().asFile.delete() }
}

tasks
    .matching { it.name == "kotlinStorePackageLock" || it.name == "kotlinUpgradePackageLock" }
    .configureEach {
        enabled = false
    }

val fixturePath =
    tasks.register("generateFixturePath") {
        val output = layout.buildDirectory.file("generated/fixture-path/ContractFixturePath.kt")
        outputs.file(output)
        val fixtureRoot = rootDir.parentFile.resolve("tests/fixtures/contract").absolutePath
        inputs.property("fixtureRoot", fixtureRoot)
        doLast {
            output.get().asFile.apply {
                parentFile.mkdirs()
                writeText(
                    "package app.powerlog.core.analytics\ninternal const val CONTRACT_FIXTURES = \"$fixtureRoot\"\n"
                )
            }
        }
    }

tasks.matching { it.name.startsWith("compileTestKotlin") }.configureEach { dependsOn(fixturePath) }

if (providers.gradleProperty("powerLogEveryday").isPresent)
    tasks.withType<AbstractTestTask>().configureEach { filter.excludeTestsMatching("*VolumeTest.*") }

tasks
    .matching {
        it.name in setOf("jvmTest", "macosArm64Test", "jsNodeTest", "iosSimulatorArm64Test")
    }
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
