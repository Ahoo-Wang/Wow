description = "Wow OpenAPI Specification"

dependencies {
    api(project(":wow-core"))
    implementation(kotlin("reflect"))
    api("io.swagger.core.v3:swagger-core-jakarta")
    testImplementation(project(":wow-tck"))
}
