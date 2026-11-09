import org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile

plugins { id("powerlog.kmp") }

kotlin {
    explicitApiWarning()
    sourceSets.commonMain.dependencies { api(project(":domain")) }
}

tasks
    .withType<Kotlin2JsCompile>()
    .matching { it.name == "compileKotlinJs" || it.name == "compileTestKotlinJs" }
    .configureEach { friendPaths.from(project(":domain").layout.buildDirectory.dir("classes/kotlin/js/main")) }
