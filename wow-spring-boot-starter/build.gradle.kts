plugins {
    kotlin("plugin.spring")
    kotlin("kapt")
}
java {
    registerFeature("r2dbcSupport") {
        usingSourceSet(sourceSets[SourceSet.MAIN_SOURCE_SET_NAME])
        capability(group.toString(), "r2dbc-support", version.toString())
    }
    registerFeature("mongoSupport") {
        usingSourceSet(sourceSets[SourceSet.MAIN_SOURCE_SET_NAME])
        capability(group.toString(), "mongo-support", version.toString())
    }
    registerFeature("kafkaSupport") {
        usingSourceSet(sourceSets[SourceSet.MAIN_SOURCE_SET_NAME])
        capability(group.toString(), "kafka-support", version.toString())
    }

    registerFeature("webfluxSupport") {
        usingSourceSet(sourceSets[SourceSet.MAIN_SOURCE_SET_NAME])
        capability(group.toString(), "webflux-support", version.toString())
    }

    registerFeature("elasticsearchSupport") {
        usingSourceSet(sourceSets[SourceSet.MAIN_SOURCE_SET_NAME])
        capability(group.toString(), "elasticsearch-support", version.toString())
    }
    registerFeature("opentelemetrySupport") {
        usingSourceSet(sourceSets[SourceSet.MAIN_SOURCE_SET_NAME])
        capability(group.toString(), "opentelemetry-support", version.toString())
    }
}
dependencies {
    kapt(platform(project(":wow-dependencies")))
    api(project(":wow-core"))
    api(project(":wow-spring"))
    "r2dbcSupportImplementation"(project(":wow-r2dbc"))
    "mongoSupportImplementation"(project(":wow-mongo"))
    "kafkaSupportImplementation"(project(":wow-kafka"))
    "webfluxSupportImplementation"(project(":wow-webflux"))
    "elasticsearchSupportImplementation"(project(":wow-elasticsearch"))
    "opentelemetrySupportImplementation"(project(":wow-opentelemetry"))
    api("org.springframework:spring-webflux")
    api("org.springframework.boot:spring-boot-starter")
    api("org.springframework.cloud:spring-cloud-commons")
    kapt("org.springframework.boot:spring-boot-configuration-processor")
    kapt("org.springframework.boot:spring-boot-autoconfigure-processor")
    testImplementation(project(":wow-test"))
    testImplementation("org.mariadb:r2dbc-mariadb")
    testImplementation(project(":wow-r2dbc"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux")
    testImplementation("org.springdoc:springdoc-openapi-webflux-core")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:mongodb")
}
