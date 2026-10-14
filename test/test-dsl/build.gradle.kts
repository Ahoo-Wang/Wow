description = "Wow test DSL: the aggregate and processor specs that run a domain on the kernel"

// The test DSL (decision D196), out of :kernel's test fixtures; it builds on the kernel only (06-modules.md). Its
// artifact is wow-test: a project named :test would clash with the root build's `test` task (decision D87).
dependencies {
    api(project(":kernel"))
}
