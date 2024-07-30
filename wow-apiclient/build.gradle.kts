description = "Wow RESTful ApiClient"

dependencies {
    api(project(":wow-core"))
    api(project(":wow-openapi"))
    api("io.projectreactor:reactor-core")
    implementation("me.ahoo.coapi:coapi-api")
    implementation("org.springframework:spring-web")
    implementation("org.springframework:spring-webflux")
    testImplementation(project(":wow-tck"))
}
