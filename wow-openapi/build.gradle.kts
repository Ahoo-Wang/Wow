description = "Wow OpenAPI Specification"

dependencies {
    api(project(":wow-core"))
    api(project(":wow-query"))
    api(project(":wow-schema"))
    implementation(project(":wow-bi"))
    implementation(kotlin("reflect"))
    implementation("org.springframework:spring-web")
    api("io.swagger.core.v3:swagger-core-jakarta")
    testImplementation(project(":example-domain"))
    testImplementation(project(":example-transfer-domain"))
    testImplementation(project(":wow-tck"))
}
