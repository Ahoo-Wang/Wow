dependencies {
    api(project(":wow-core"))
    api("org.springframework.data:spring-data-elasticsearch")
    testImplementation(project(":wow-tck"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:elasticsearch")
    testImplementation("org.springframework:spring-webflux")
    testImplementation("io.projectreactor.netty:reactor-netty-http")
}
