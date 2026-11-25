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
    implementation("io.github.oshai:kotlin-logging-jvm")
    testImplementation(project(":query-tck"))
    integrationTestImplementation(project(":tck"))
    integrationTestImplementation(project(":tck-containers"))
    integrationTestImplementation(project(":query-tck"))
}

tasks.named<Test>("integrationTest") {
    // ElasticsearchImageTest holds the GA end-to-end cluster's Elasticsearch image to the tests' one.
    val gaE2e = rootProject.file(".github/ga-e2e/backends/elasticsearch.yaml")
    inputs.file(gaE2e).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("gaE2eYaml")
    systemProperty("wow-test.elasticsearch.ga-e2e-yaml", gaE2e.absolutePath)
}

// SkillEvalDescriptorsTest holds the descriptors in the view and query Skills' evaluation prompts to the query routes'
// (#6984): declared, so a changed prompt runs it instead of reusing a cached result.
tasks.named<Test>("test") {
    inputs.files(
        rootProject.fileTree("skills") {
            include("wow-view-definition/evals/**/prompt.md", "wow-data-query/evals/**/prompt.md")
        },
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("skillEvalPrompts")
}
