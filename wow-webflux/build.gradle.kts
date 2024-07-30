dependencies {
    api(project(":wow-core"))
    api(project(":wow-openapi"))
    implementation(project(":wow-bi"))
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-webflux")
    testImplementation(project(":wow-tck"))
}
