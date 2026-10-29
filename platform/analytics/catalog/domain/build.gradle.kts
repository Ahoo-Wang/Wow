description = "Wow analytics catalog: the source aggregate and the warehouse catalog it reads (roadmap 9.3b)"

// The analytics catalog's domain (decisions D238, D241). The plugin `me.ahoo.wow` compiles the aggregate into its
// kernel model, the binding index and the module's Manifest fragment, and offers its contract to
// :analytics-catalog-api, as the other platform domains do.
plugins {
    id("me.ahoo.wow")
}

wow {
    service = "analytics-catalog-service"
}

dependencies {
    api(project(":analytics-catalog-api"))
    implementation("me.ahoo.wow:wow-query-api")
    testImplementation("me.ahoo.wow:wow-test")
    // The real query side the recompilation saga reads its datasets from.
    testImplementation("me.ahoo.wow:wow-test-query")
    // The Manifest fragment the compiler writes, which the tests read (the kernel does not bring :manifest, D231).
    testImplementation("me.ahoo.wow:wow-manifest")
}
