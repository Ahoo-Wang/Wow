plugins {
    alias(libs.plugins.kotlin.spring)
    kotlin("kapt")
}

dependencies {
    kapt(platform(project(":wow-dependencies")))
    api(project(":wow-dataset-registry-domain"))
    api(project(":wow-spring-boot-starter"))
    api(project(":wow-query"))
    compileOnly(project(":wow-clickhouse"))
    compileOnly(project(":wow-webflux"))
    kapt("org.springframework.boot:spring-boot-configuration-processor")
    kapt("org.springframework.boot:spring-boot-autoconfigure-processor")
    testImplementation(project(":wow-test"))
    testImplementation(project(":wow-clickhouse"))
    testImplementation(project(":wow-webflux"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    integrationTestImplementation("org.testcontainers:testcontainers-clickhouse")
}
