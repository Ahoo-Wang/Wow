dependencies {
    api(project(":wow-core"))
    api(project(":wow-query"))
    api("org.springframework.data:spring-data-elasticsearch")
    testImplementation(project(":wow-tck"))
    testImplementation("org.springframework:spring-webflux")
    testImplementation("io.projectreactor.netty:reactor-netty-http")
}
