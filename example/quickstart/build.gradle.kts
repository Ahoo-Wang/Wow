description = "Wow V10 quickstart: the bank service of the quickstart page, its domain and its server in one module"

// The sample of documentation/docs/{en,zh}/guide/v10/quickstart.md (decision D217). `./gradlew :quickstart:run` starts
// it on the memory backend. Never published. The page applies the Gradle plugin `me.ahoo.wow`; the plugin cannot be
// applied inside the build that produces it, so this applies KSP and passes the plugin's options by hand, as the eShop
// does.
plugins {
    alias(libs.plugins.ksp)
    application
}

application {
    mainClass.set("me.ahoo.wow.example.quickstart.BankServerKt")
}

ksp {
    arg("wow.model", "generate")
    arg("wow.manifest.module", project.name)
}

// The plugin's completion of the fragment with the schemas of the types, so the snapshot queries know the state.
apply(from = rootProject.file("example/wow-manifest-fragment.gradle.kts"))

dependencies {
    implementation(project(":kernel"))
    implementation(project(":spring-boot"))
    ksp(project(":compiler"))
    testImplementation(project(":test-dsl"))
    testImplementation("org.springframework:spring-test")
}

// QuickstartPageTest keeps the page's Kotlin and YAML samples equal to these sources.
val quickstartPages = listOf("en", "zh").map { rootProject.file("documentation/docs/$it/guide/v10/quickstart.md") }
tasks.test {
    inputs.files(quickstartPages).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("quickstartPages")
    systemProperty("wow.quickstart.module", projectDir.absolutePath)
    systemProperty("wow.quickstart.pages", quickstartPages.joinToString(File.pathSeparator) { it.absolutePath })
}
