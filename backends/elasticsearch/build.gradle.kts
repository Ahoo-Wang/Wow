description = "Wow Elasticsearch: snapshots, their queries and the event search Sink on Elasticsearch"

// Work package 6.7 (decisions D13, D131, D167): no log on Elasticsearch, only snapshots, their queries and event search.
dependencies {
    api(project(":spi"))
    // The snapshot query backend (decision D131).
    api(project(":query"))
    // The official client, managed by the Spring Boot BOM that wow-dependencies imports (9.x's wow-elasticsearch used it
    // through Spring Data); a store opens over the application's low-level Rest5Client.
    api("co.elastic.clients:elasticsearch-java")
    implementation("tools.jackson.core:jackson-databind")
    testImplementation(project(":query-tck"))
    integrationTestImplementation(project(":tck"))
    integrationTestImplementation(project(":tck-containers"))
    integrationTestImplementation(project(":query-tck"))
}
