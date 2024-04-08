description = "Wow RESTfull ApiClient"

dependencies {
    api(project(":wow-core"))
    api(project(":wow-query"))
    implementation("org.springframework:spring-web")
    api("io.swagger.core.v3:swagger-core-jakarta")
    testImplementation(project(":wow-tck"))
}
