description = "Wow RESTful ApiClient"

dependencies {
    api(project(":wow-api"))
    api("io.projectreactor:reactor-core")
    implementation("org.springframework:spring-web")
    testImplementation(project(":wow-tck"))
}
