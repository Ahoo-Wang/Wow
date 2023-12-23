description = "The Technology Compatibility Kit"

dependencies {
    api(project(":wow-core"))
    api("io.projectreactor:reactor-test")
    api("me.ahoo.cosid:cosid-test")
    api("org.hamcrest:hamcrest")
    api(project(":wow-test"))
    implementation("org.junit.jupiter:junit-jupiter-api")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("io.micrometer:micrometer-core")
    implementation(kotlin("reflect"))
    implementation(project(mapOf("path" to ":wow-spring-boot-starter")))
    implementation("org.testcontainers:testcontainers")
    implementation("org.testcontainers:junit-jupiter")
    implementation("io.projectreactor.kafka:reactor-kafka")
    implementation("org.testcontainers:kafka")
    implementation("org.testcontainers:mongodb")
    implementation("org.testcontainers:elasticsearch")
}
