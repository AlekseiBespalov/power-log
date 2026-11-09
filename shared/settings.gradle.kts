pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "power-log-shared"

include(":domain", ":application", ":data", ":kit", ":runtime")

if (gradle.parent != null) {
    val androidShared = settingsDir.resolve("../android/build/power-log-shared").canonicalFile
    gradle.beforeProject {
        layout.buildDirectory.set(androidShared.resolve(name))
        extensions.extraProperties["kotlin.project.persistent.dir"] = androidShared.resolve(".kotlin").path
        extensions.extraProperties["kotlin.project.persistent.dir.gradle.disableWrite"] = "true"
    }
}
