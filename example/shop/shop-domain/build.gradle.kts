description = "Wow V10 example: the shop's cart and order aggregates"

// KSP with :compiler compiles the aggregates into kernel models, their binding index (D107) and the module's Manifest
// fragment, as the Gradle plugin `me.ahoo.wow` does for an application. The plugin cannot be applied to a project of
// the build that produces it, so this applies KSP and passes the plugin's options by hand: the model processor in
// `generate` mode, and the fragment named after the project (META-INF/wow/manifest/shop-domain.json). What the plugin
// adds besides comes from scripts: the schemas in the fragment from example/wow-manifest-fragment.gradle.kts, below,
// and the merged manifest.json from the server's example/wow-peers.gradle.kts.
plugins {
    alias(libs.plugins.ksp)
}

ksp {
    arg("wow.model", "generate")
    arg("wow.manifest.module", project.name)
}

// The plugin's completion of the fragment with the schemas of the types (decision D73), so the Manifest carries the
// state schemas the snapshot queries read.
apply(from = rootProject.file("example/wow-manifest-fragment.gradle.kts"))

dependencies {
    api(project(":shop-api"))
    // The inventory's commands, which the order saga sends to the inventory context (decision D144).
    implementation(project(":inventory-api"))
    // The generated models, the processor bindings and the binding index build on the kernel only (decision D149).
    api(project(":kernel"))
    // The query annotations and the query DSL; with it, the compiler generates each state's <State>Properties.
    api(project(":query-api"))
    ksp(project(":compiler"))
    // The aggregate tests run the domain on the kernel, with its test DSL.
    testImplementation(testFixtures(project(":kernel")))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
}
