description = "Wow ClickHouse dataset query backend (S0 spike, not published)"

dependencies {
    api(project(":wow-query"))
    api("io.projectreactor:reactor-core")
    implementation(libs.clickhouse.client.v2)
    integrationTestImplementation("io.projectreactor:reactor-test")
    integrationTestImplementation("org.testcontainers:testcontainers-clickhouse")
    integrationTestImplementation("org.testcontainers:testcontainers-junit-jupiter")
}
