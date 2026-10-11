description = "Wow ClickHouse dataset query backend"

dependencies {
    api(project(":wow-query"))
    api("io.projectreactor:reactor-core")
    api(libs.clickhouse.client.v2)
    testImplementation("io.projectreactor:reactor-test")
    integrationTestImplementation(project(":wow-tck"))
    integrationTestImplementation("io.projectreactor:reactor-test")
    integrationTestImplementation("org.testcontainers:testcontainers-clickhouse")
}
