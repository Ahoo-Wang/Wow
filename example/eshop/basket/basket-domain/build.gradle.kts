description = "Wow eShop: the basket context's aggregates"

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
// The service that hosts the contexts, which a peer of the api module finds by this name (decision D330); the
// plugin's `wow { contract { service = "eshop-basket-service" } }`.
extra["wowContractService"] = "eshop-basket-service"
apply(from = rootProject.file("example/wow-manifest-fragment.gradle.kts"))

dependencies {
    api(project(":eshop-basket-api"))
    // The ordering context's commands, which the checkout saga calls it with.
    implementation(project(":eshop-ordering-api"))
    // The generated models and the binding index build on the kernel only (decision D149).
    api(project(":kernel"))
    ksp(project(":compiler"))
    testImplementation(project(":test-dsl"))
}
