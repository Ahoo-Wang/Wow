description = "Wow V10 ops: the parked aggregate, the read model of every service's failure ledger (roadmap 6.3f, 7.1a)"

// The minimal ops domain of work package 6.3f (decision D141); roadmap 7.1a builds the ops service on it. KSP with
// :compiler compiles the aggregate and the saga into kernel models, their binding index and the module's Manifest
// fragment, as the Gradle plugin `me.ahoo.wow` does for an application; the plugin cannot be applied to a project of
// the build that produces it, so this applies KSP and passes its options by hand, as the eShop's domains do.
plugins {
    alias(libs.plugins.ksp)
}

ksp {
    arg("wow.model", "generate")
    arg("wow.manifest.module", project.name)
}

// The plugin's completion of the fragment with the schemas of the types (decision D73): the parked state's schema is
// what a query of the ops view reads.
apply(from = rootProject.file("example/wow-manifest-fragment.gradle.kts"))

dependencies {
    api(project(":ops-api"))
    // The generated models and the binding index build on the kernel only (decision D149).
    api(project(":kernel"))
    ksp(project(":compiler"))
    testImplementation(project(":test-dsl"))
}
