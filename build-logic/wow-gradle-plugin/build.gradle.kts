plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin)
}

val plugin = rootDir.resolve("../../compiler/gradle-plugin")
// The version of Wow the plugin adds its modules at: the root build's, which the substitution maps to its projects.
val wowVersion: String = providers.fileContents(layout.projectDirectory.file("../../gradle.properties")).asText
    .map { text -> text.lines().single { it.startsWith("version=") }.substringAfter('=').trim() }.get()

sourceSets.main {
    kotlin.srcDir(plugin.resolve("src/main/kotlin"))
    resources.srcDir(plugin.resolve("src/main/resources"))
}

kotlin {
    jvmToolchain(21)
}

// The plugin ids and classes of compiler/gradle-plugin/build.gradle.kts.
gradlePlugin {
    plugins {
        create("wow") {
            id = "me.ahoo.wow"
            implementationClass = "me.ahoo.wow.gradle.WowPlugin"
        }
        create("wowApi") {
            id = "me.ahoo.wow.api"
            implementationClass = "me.ahoo.wow.gradle.WowApiPlugin"
        }
        create("wowService") {
            id = "me.ahoo.wow.service"
            implementationClass = "me.ahoo.wow.gradle.WowServicePlugin"
        }
    }
}

dependencies {
    implementation("com.google.devtools.ksp:symbol-processing-gradle-plugin:${libs.versions.ksp.get()}")
}

tasks.processResources {
    val kotlinVersion = libs.versions.kotlin.asProvider().get()
    inputs.property("version", wowVersion)
    inputs.property("kotlinVersion", kotlinVersion)
    filesMatching("**/wow-gradle-plugin.properties") {
        expand("version" to wowVersion, "kotlinVersion" to kotlinVersion)
    }
}
