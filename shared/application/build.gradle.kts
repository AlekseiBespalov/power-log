plugins { id("powerlog.kmp") }

kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":domain"))
        api(libs.kotlinx.coroutines.core)
        api(libs.kotlinx.serialization.core)
    }
    sourceSets.jvmTest.dependencies {
        implementation(project(":data"))
        implementation(libs.kotlin.reflect)
        implementation(libs.kotlinx.serialization.json)
        implementation(libs.kotlinx.coroutines.test)
    }
}

tasks.register<JavaExec>("generateApiTypes") {
    val test = kotlin.jvm().compilations.getByName("test")
    classpath = test.output.allOutputs + test.runtimeDependencyFiles!!
    mainClass.set("app.powerlog.application.api.generator.GenerateApiTypesKt")
    val output = layout.buildDirectory.dir("generated/api")
    args(output.get().asFile.absolutePath)
    outputs.dir(output)
}

tasks.named<Test>("jvmTest") {
    val repository = rootDir.parentFile
    systemProperty("powerlog.repositoryRoot", repository.absolutePath)
    systemProperty("powerlog.ridePages", rootDir.resolve("build/ride-golden-pages").absolutePath)
    systemProperty(
        "powerlog.goldensHome",
        providers
            .environmentVariable("POWER_LOG_GOLDENS_HOME")
            .orElse("${System.getProperty("user.home")}/.cache/power-log/goldens")
            .get(),
    )
    inputs
        .dir(repository.resolve("tests/fixtures/export"))
        .withPropertyName("exportFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    if (providers.gradleProperty("powerLogRideGoldens").isPresent) outputs.upToDateWhen { false }
    else {
        filter.excludeTestsMatching("app.powerlog.application.exports.RideGoldenPagesTest")
        filter.isFailOnNoMatchingTests = false
    }
}
