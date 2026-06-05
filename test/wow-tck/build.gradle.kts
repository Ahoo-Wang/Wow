description = "The Technology Compatibility Kit"

dependencies {
    api(project(":wow-core"))
    api(project(":wow-query"))
    api("io.projectreactor:reactor-test")
    api("me.ahoo.cosid:cosid-test")
    api(project(":wow-test"))
    api("org.junit.jupiter:junit-jupiter-api")
    implementation("io.micrometer:micrometer-core")
    implementation(kotlin("reflect"))
    api(libs.reactor.kafka)
    api("org.mongodb:mongodb-driver-reactivestreams")
    api("org.springframework.data:spring-data-redis")
    api("io.lettuce:lettuce-core")
    api("org.testcontainers:testcontainers")
    implementation("org.testcontainers:testcontainers-junit-jupiter")
    api("org.testcontainers:testcontainers-kafka")
    api("org.testcontainers:testcontainers-mongodb")
    api("org.testcontainers:testcontainers-elasticsearch")
    api("org.testcontainers:testcontainers-mariadb")
}
