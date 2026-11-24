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
    // What the published markers of me.ahoo.wow and me.ahoo.wow.service bring: the plugin's jar declares no KSP.
    runtimeOnly("com.google.devtools.ksp:symbol-processing-gradle-plugin:${libs.versions.ksp.get()}")
}

// The wow-service chart in the plugin's resources, as compiler/gradle-plugin puts it there.
extra["wowChartDirectory"] = rootDir.resolve("../../deploy/helm/wow-service")
extra["wowChartVersion"] = wowVersion
apply(from = plugin.resolve("wow-service-chart.gradle.kts"))

tasks.processResources {
    val kotlinVersion = libs.versions.kotlin.asProvider().get()
    val kspVersion = libs.versions.ksp.get()
    inputs.property("version", wowVersion)
    inputs.property("kotlinVersion", kotlinVersion)
    inputs.property("kspVersion", kspVersion)
    filesMatching("**/wow-gradle-plugin.properties") {
        expand("version" to wowVersion, "kotlinVersion" to kotlinVersion, "kspVersion" to kspVersion)
    }
}
