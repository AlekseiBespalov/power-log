plugins { id("powerlog.kmp") }

kotlin {
    explicitApiWarning()
    sourceSets.commonMain.dependencies { api(libs.kotlinx.serialization.core) }
}
