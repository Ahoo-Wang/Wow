description = "Wow Kafka: the Kafka transport, Kafka Sink and Kafka-sourced subscriptions"

// The transport over kafka-clients (decision D31); the runtime's Kafka Sink writes through it (decision D132).
dependencies {
    api(project(":spi"))
    // Managed by the Spring Boot BOM that wow-dependencies imports; 9.x wow-kafka reaches it through reactor-kafka.
    api("org.apache.kafka:kafka-clients")
    integrationTestImplementation(testFixtures(project(":spi")))
    integrationTestImplementation("org.testcontainers:testcontainers-kafka")
}
