description = "Wow RESTful ApiClient"

dependencies {
    api(project(":wow-core"))
    api(project(":wow-rest-contract"))
    api(project(":wow-query"))
    api("io.projectreactor:reactor-core")
    implementation("me.ahoo.coapi:coapi-api")
    implementation("org.springframework:spring-web")
    implementation("org.springframework:spring-webflux")
    testImplementation(project(":wow-tck"))
}
