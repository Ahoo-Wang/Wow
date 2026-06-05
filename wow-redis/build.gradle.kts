dependencies {
    api(project(":wow-core"))
    api("org.springframework.data:spring-data-redis")
    api("io.lettuce:lettuce-core")
    testImplementation(project(":wow-tck"))
}
