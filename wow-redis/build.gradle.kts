dependencies {
    api(project(":wow-core"))
    api("org.springframework.data:spring-data-redis")
    implementation("io.lettuce:lettuce-core")
    testImplementation(project(":wow-tck"))

}
