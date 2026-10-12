description = "Wow V10 example: the shop's cart and order aggregates"

// KSP with :compiler compiles the aggregates into kernel models, their binding index (D107) and the module's Manifest
// fragment, as the Gradle plugin `me.ahoo.wow` does for an application. The plugin cannot be applied to a project of
// the build that produces it, so this applies KSP and passes the plugin's options by hand: the model processor in
// `generate` mode, and the fragment named after the project (META-INF/wow/manifest/shop-domain.json). What the plugin
// adds besides (schemas in the fragment, the merged manifest.json) belongs to the server's build.
plugins {
    alias(libs.plugins.ksp)
}

ksp {
    arg("wow.model", "generate")
    arg("wow.manifest.module", project.name)
}

dependencies {
    api(project(":shop-api"))
    // The generated models and the binding index build on the kernel.
    api(project(":kernel"))
    // The generated subscription bindings of CartSaga build on the runtime's binding API.
    api(project(":runtime"))
    ksp(project(":compiler"))
    // The aggregate tests run the domain on the kernel, with its test DSL.
    testImplementation(testFixtures(project(":kernel")))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
}
