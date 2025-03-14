dependencies {
    api(project(":wow-core"))
    api(project(":wow-openapi"))
    implementation(project(":wow-bi"))
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-webflux")
    testImplementation("org.springframework:spring-test")
    testImplementation(project(":wow-tck"))
    testImplementation(project(":example-domain"))

}
