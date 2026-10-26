description = "Wow HTTP: routes materialized from the Manifest, problem+json, SSE and the signal entry"

dependencies {
    api(project(":runtime"))
    // The query routes (decision D127) run the query engine's gateways and read its protocol with QueryJson.
    api(project(":query"))
    // GET /wow/openapi: the OpenAPI document of the routes, from the same Manifest (roadmap 5a.2).
    implementation(project(":openapi"))
    api("org.springframework:spring-webflux")
    // The internal port's own server (decisions D89, D109), whose security floor every host gets (review R5.C-2).
    implementation("io.projectreactor.netty:reactor-netty-http")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    // The wait signal's wire form on the internal port, read like the cluster messages (decision D105).
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("io.github.oshai:kotlin-logging-jvm")
    // The Jakarta Bean Validation adapter of CommandValidator; the host enables it where a provider is present (D106).
    compileOnly("jakarta.validation:jakarta.validation-api")
    testImplementation("jakarta.validation:jakarta.validation-api")
    // WebTestClient, bound to a router function or to a Reactor Netty server.
    testImplementation("org.springframework:spring-test")
    testImplementation("io.projectreactor:reactor-test")
    // The default handler strategies of a router function served without Spring Boot read the locale context.
    testImplementation("org.springframework:spring-context")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    // The in-memory reference query backend, behind the snapshot query routes.
    testImplementation(project(":query-tck"))
    // The typed clients, as a peer calls the routes (the main code shares only the wire grammar of `:api`, D286).
    testImplementation(project(":client"))
    testImplementation(testFixtures(project(":manifest")))
    // The answers of the routes, checked against the schemas of the document GET /wow/openapi serves.
    testImplementation(libs.json.schema.validator)
}
