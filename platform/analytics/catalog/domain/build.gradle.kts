description = "Wow analytics catalog: the source aggregate and the warehouse catalog it reads (roadmap 9.3b)"

// The analytics catalog's domain (decisions D238, D241). KSP with :compiler compiles the aggregate into its kernel
// model, the binding index and the module's Manifest fragment, as the Gradle plugin `me.ahoo.wow` does for an
// application; the plugin cannot be applied to a project of the build that produces it, so this applies KSP and passes
// its options by hand, as the other platform domains do.
plugins {
    alias(libs.plugins.ksp)
}

ksp {
    arg("wow.model", "generate")
    arg("wow.manifest.module", project.name)
}

// The plugin's completion of the fragment with the schemas of the types (decision D73): the source's state schema is
// what a query of the sources reads.
// The service that hosts the contexts, which a peer of the api module finds by this name (decision D330); the
// plugin's `wow { contract { service = "analytics-catalog-service" } }`.
extra["wowContractService"] = "analytics-catalog-service"
apply(from = rootProject.file("example/wow-manifest-fragment.gradle.kts"))

dependencies {
    api(project(":analytics-catalog-api"))
    // The generated models and the binding index build on the kernel only (decision D149).
    api(project(":kernel"))
    implementation(project(":query-api"))
    ksp(project(":compiler"))
    testImplementation(project(":test-dsl"))
    // The real query side the recompilation saga reads its datasets from.
    testImplementation(project(":test-query"))
    // The Manifest fragment the compiler writes, which the tests read (the kernel does not bring :manifest, D231).
    testImplementation(project(":manifest"))
}
