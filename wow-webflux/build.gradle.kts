dependencies {
    api(project(":wow-core"))
    implementation("org.springframework:spring-webflux")
    implementation("org.springdoc:springdoc-openapi-webflux-core")
    testImplementation(project(":wow-tck"))
}
