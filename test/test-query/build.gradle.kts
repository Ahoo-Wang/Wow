description = "Wow test queries: a real in-memory QueryApi for the processor specs of the test DSL"

// The bridge between the test DSL and the query engine (06-modules.md): :test-dsl stays on the kernel only, so the
// specs that read the query side take this module, which runs the engine over the in-memory query storage.
dependencies {
    api(project(":test-dsl"))
    api(project(":query"))
}
