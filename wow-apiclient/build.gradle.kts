description = "Wow RESTfull ApiClient"

dependencies {
    api(project(":wow-api"))
    api("io.projectreactor:reactor-core")
    implementation("org.springframework:spring-web")
    api("io.swagger.core.v3:swagger-core-jakarta")
    testImplementation(project(":wow-tck"))
}
