description = "Wow V10 eShop: the catalog context's aggregates"

// KSP with :compiler compiles the aggregates into kernel models, their binding index (D107) and the module's Manifest
// fragment, as the Gradle plugin `me.ahoo.wow` does for an application. The plugin cannot be applied to a project of
// the build that produces it, so this applies KSP and passes the plugin's options by hand, as the shop does.
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
    api(project(":eshop-catalog-api"))
    // The generated models and the binding index build on the kernel only (decision D149).
    api(project(":kernel"))
    ksp(project(":compiler"))
    testImplementation(testFixtures(project(":kernel")))
}
