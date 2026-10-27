description = "Wow view store: the view, view preferences and dataset wording aggregates (roadmap 7.2a)"

// The 9.x view store's domain (view-store/wow-view-store-domain), ported with the migration Skill (roadmap M7, H5).
// KSP with :compiler compiles the aggregates and the saga into kernel models, their binding index and the module's
// Manifest fragment, as the Gradle plugin `me.ahoo.wow` does for an application; the plugin cannot be applied to a
// project of the build that produces it, so this applies KSP and passes its options by hand, as the eShop's domains do.
plugins {
    alias(libs.plugins.ksp)
}

ksp {
    arg("wow.model", "generate")
    arg("wow.manifest.module", project.name)
}

// The plugin's completion of the fragment with the schemas of the types (decision D73): the view's state schema is
// what a query of the views reads.
// The service that hosts the contexts, which a peer of the api module finds by this name (decision D330); the
// plugin's `wow { contract { service = "view-store-service" } }`.
extra["wowContractService"] = "view-store-service"
apply(from = rootProject.file("examples/wow-manifest-fragment.gradle.kts"))

dependencies {
    api(project(":view-store-api"))
    // The generated models and the binding index build on the kernel only (decision D149).
    api(project(":kernel"))
    ksp(project(":compiler"))
    testImplementation(project(":test-dsl"))
    // The Manifest fragment the compiler writes, which the tests read (the kernel does not bring :manifest, D231).
    testImplementation(project(":manifest"))
}
