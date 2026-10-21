description = "Wow models: shared capability interfaces, EnumText and the reusable Tree aggregate (decision D187)"

// The port of 9.x wow-models (work package P.8): the same packages, on the 10.x wow-api only. The Tree aggregate's
// handlers live in its superclasses; a domain's subclass binds them when the compiler processes the domain. The tests
// do that with a sample tree: KSP with :compiler compiles it as the Gradle plugin `me.ahoo.wow` would.
plugins {
    alias(libs.plugins.ksp)
}

ksp {
    arg("wow.model", "generate")
    arg("wow.manifest.module", project.name)
}

dependencies {
    api(project(":api"))
    // @JsonIgnore on the computed properties of the tree's commands, as in 9.x.
    api("com.fasterxml.jackson.core:jackson-annotations")
    // The not-blank constraints of the capabilities, which an HTTP edge with Jakarta Validation checks.
    api("jakarta.validation:jakarta.validation-api")
    kspTest(project(":compiler"))
    testImplementation(project(":test-dsl"))
    // The Manifest fragment the compiler writes, which the tests read (the kernel does not bring :manifest, D231).
    testImplementation(project(":manifest"))
}
