dependencies {
    api(project(":wow-core"))
    implementation("org.springframework:spring-webflux")
    implementation("org.springdoc:springdoc-openapi-starter-webflux-api")
    testImplementation(project(":wow-tck"))
}
