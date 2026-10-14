description = "Wow TCK containers: the Testcontainers the backends' integration tests run the TCK specs against"

// The shared container fixtures of the TCK (decision D196), apart from :tck so a spec stays free of Docker: the
// in-memory backend runs the specs with :tck alone. Every artifact is managed by the Testcontainers BOM that
// wow-dependencies imports, and the backends' integration tests used each before.
dependencies {
    api("org.testcontainers:testcontainers-postgresql")
    api("org.testcontainers:testcontainers-mongodb")
    api("org.testcontainers:testcontainers-kafka")
    api("org.testcontainers:testcontainers-clickhouse")
}
