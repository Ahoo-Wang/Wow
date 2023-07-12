dependencies {
    api(project(":wow-core"))
    api(project(":wow-openapi"))
    implementation("org.springframework:spring-webflux")
    testImplementation(project(":wow-tck"))
}
