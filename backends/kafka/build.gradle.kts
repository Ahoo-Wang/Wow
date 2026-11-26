description = "Wow Kafka: the Kafka transport, Kafka Sink and Kafka-sourced subscriptions"

// The transport over kafka-clients (decision D31); the runtime's Kafka Sink writes through it (decision D132).
dependencies {
    // The guard of the hand-written equals (finding R5.B-13).
    testImplementation(project(":tck"))
    api(project(":spi"))
    // Managed by the Spring Boot BOM that wow-dependencies imports; 9.x wow-kafka reaches it through reactor-kafka.
    api("org.apache.kafka:kafka-clients")
    // kafka-clients 4.4.0 brings snappy-java 1.1.10.7, open to GHSA-gwq9-9ffj-mvrv, GHSA-xqrx-qh46-34m7 and
    // GHSA-q233-g92q-qxp6, which a consumer meets reading a snappy batch. Declared here at the patched version, so that
    // it wins for a Maven consumer (the nearer declaration) as for a Gradle one (the higher version): a constraint alone
    // would not reach Maven. Remove it once kafka-clients itself brings 1.1.10.9 or later.
    runtimeOnly(libs.snappy.java)
    // kafka-clients 4.4.0 brings lz4-java 1.11.2, open to GHSA-mcr4-qmvw-px4g, GHSA-343h-94h5-c4wr and
    // GHSA-gm45-99xc-r7wv (an lz4 batch, read or written). Declared at the patched version for the same reason; remove it
    // once kafka-clients itself brings 1.11.4 or later.
    runtimeOnly(libs.lz4.java)
    integrationTestImplementation(project(":tck"))
    integrationTestImplementation(project(":tck-containers"))
}
