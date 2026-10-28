description = "Wow eShop: the payment context's aggregates"

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
// plugin's `wow { contract { service = "eshop-payment-service" } }`.
extra["wowContractService"] = "eshop-payment-service"
apply(from = rootProject.file("examples/wow-manifest-fragment.gradle.kts"))

dependencies {
    api(project(":eshop-payment-api"))
    // The ordering's commands, which the order-payment saga sends to the ordering context.
    implementation(project(":eshop-ordering-api"))
    // The generated models and the binding index build on the kernel only (decision D149).
    api(project(":kernel"))
    ksp(project(":compiler"))
    testImplementation(project(":test-dsl"))
    // The Manifest fragment the compiler writes, which the tests read (the kernel does not bring :manifest, D231).
    testImplementation(project(":manifest"))
    // The sagas' suspending handler and an `@OnRejected` handler's binding are invoked from a coroutine.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
}
