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
    integrationTestImplementation(project(":wow-tck"))
    integrationTestImplementation(project(":wow-mongo"))
    integrationTestImplementation("me.ahoo.cosid:cosid-spring-boot-starter")
    integrationTestImplementation("org.springframework.boot:spring-boot-starter-actuator")
    integrationTestImplementation("org.springframework.boot:spring-boot-starter-validation")
    integrationTestImplementation("org.springframework.boot:spring-boot-starter-data-mongodb-reactive")
    integrationTestImplementation("org.springframework.boot:spring-boot-starter-webflux")
}
