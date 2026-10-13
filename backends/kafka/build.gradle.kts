description = "Wow Kafka: the Kafka transport, Kafka Sink and Kafka-sourced subscriptions"

// The skeleton of work package 6.00; the transport and the Sink come with 6.3a (decision D132).
dependencies {
    api(project(":spi"))
    // Managed by the Spring Boot BOM that wow-dependencies imports; 9.x wow-kafka reaches it through reactor-kafka.
    api("org.apache.kafka:kafka-clients")
}
