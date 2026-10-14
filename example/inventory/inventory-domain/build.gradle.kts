description = "Wow V10 example: the inventory's stock aggregate and its saga on the shop's orders"

// KSP with :compiler compiles the aggregate into its kernel model, the binding index (D107) and the module's Manifest
// fragment, as the Gradle plugin `me.ahoo.wow` does for an application; the plugin cannot be applied to a project of
// the build that produces it, so this passes its options by hand, as the shop does.
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
    api(project(":inventory-api"))
    // The generated model, the processor bindings and the binding index build on the kernel only (decision D149).
    api(project(":kernel"))
    ksp(project(":compiler"))
    testImplementation(project(":test-dsl"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
}
