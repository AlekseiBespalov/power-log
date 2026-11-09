import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree

plugins { id("powerlog.kmp") }

apply(plugin = "com.android.library")

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        instrumentedTestVariant.sourceSetTree.set(KotlinSourceSetTree.test)
    }
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("sqlite") {
                group("java") {
                    withAndroidTarget()
                    withJvm()
                }
                group("apple")
            }
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":application"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies { implementation(libs.kotlinx.coroutines.test) }
        getByName("sqliteMain").dependencies { implementation(libs.androidx.sqlite) }
        androidMain.dependencies { implementation(libs.androidx.sqlite.framework) }
        appleMain.dependencies { implementation(libs.androidx.sqlite.framework) }
        jvmMain.dependencies { implementation(libs.androidx.sqlite.bundled) }
        getByName("androidUnitTest") {
            kotlin.srcDir("src/androidSharedTest/kotlin")
            dependencies {
                implementation(libs.junit)
                implementation(libs.robolectric)
                implementation(libs.androidx.test.core)
                implementation(libs.androidx.test.junit)
            }
        }
        getByName("androidInstrumentedTest") {
            kotlin.srcDir("src/androidSharedTest/kotlin")
            dependencies {
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.core)
                implementation(libs.androidx.test.junit)
            }
        }
    }
}

extensions.configure<LibraryExtension> {
    defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    testOptions.unitTests.all { test ->
        val home = rootDir.resolve("../modules/power-log/android/build/test-home")
        test.systemProperty("user.home", home.absolutePath)
        test.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
        test.doFirst { home.mkdirs() }
    }
}
