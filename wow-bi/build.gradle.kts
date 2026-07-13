description = "Wow BI Sync Script Generator"

dependencies {
    api(project(":wow-api"))
    api("io.projectreactor:reactor-core")
    implementation(project(":wow-core"))
    implementation(libs.clickhouse.client.v2)
    testImplementation("io.projectreactor:reactor-test")
    testImplementation(project(":example-domain"))
    testImplementation(project(":example-transfer-domain"))
    testImplementation(project(":wow-compensation-domain"))
    integrationTestImplementation(project(":wow-tck"))
    integrationTestImplementation("org.testcontainers:testcontainers-clickhouse")
    integrationTestImplementation(variantOf(libs.clickhouse.jdbc) { classifier("all") })
}
