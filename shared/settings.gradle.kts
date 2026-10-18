pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

rootProject.name = "power-log-core"

if (gradle.parent != null) {
    val androidCore = settingsDir.resolve("../android/build/power-log-core").canonicalFile
    gradle.beforeProject {
        layout.buildDirectory.set(androidCore)
        extensions.extraProperties["kotlin.project.persistent.dir"] = androidCore.resolve(".kotlin").path
        extensions.extraProperties["kotlin.project.persistent.dir.gradle.disableWrite"] = "true"
    }
}
