dependencies {
    api(project(":wow-core"))
    api("org.springframework.data:spring-data-redis")
    testImplementation(project(":wow-tck"))
    testImplementation("io.lettuce:lettuce-core")
}
