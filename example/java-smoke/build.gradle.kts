description = "Wow Java smoke test: a domain written in Java, built by the Wow compiler"

// Decision D229 G-8: a Java domain builds with the compiler. Its sources are Java only, under src/main/java; the
// Kotlin JVM plugin (applied to every library project by the root build) is what a Java domain module applies too,
// because KSP runs within the Kotlin compilation and the models it generates are Kotlin (the Gradle plugin
// `me.ahoo.wow` fails a module without it). The plugin cannot be applied inside the build that produces it, so this
// applies KSP and passes the plugin's options by hand, as the eShop and the quickstart do. Its tests are Kotlin: the
// test DSL has no Java API yet. Never published.
plugins {
    alias(libs.plugins.ksp)
}

ksp {
    arg("wow.model", "generate")
    arg("wow.manifest.module", project.name)
}

// The plugin's completion of the fragment with the schemas of the types.
apply(from = rootProject.file("example/wow-manifest-fragment.gradle.kts"))

dependencies {
    api(project(":kernel"))
    ksp(project(":compiler"))
    testImplementation(project(":test-dsl"))
}
