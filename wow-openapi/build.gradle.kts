description = "Wow OpenAPI Specification"

dependencies {
    api(project(":wow-core"))
    api(project(":wow-query"))
    implementation(project(":wow-bi"))
    implementation(kotlin("reflect"))
    implementation("org.springframework:spring-web")
    api("io.swagger.core.v3:swagger-core-jakarta")
    testImplementation(project(":example-domain"))
    testImplementation(project(":example-transfer-domain"))
    testImplementation(project(":wow-tck"))
}
