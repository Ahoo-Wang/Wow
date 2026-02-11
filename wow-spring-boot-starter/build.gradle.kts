plugins {
    alias(libs.plugins.kotlin.spring)
    kotlin("kapt")
}
java {
    registerFeature("mongoSupport") {
        usingSourceSet(sourceSets[SourceSet.MAIN_SOURCE_SET_NAME])
        capability(group.toString(), "mongo-support", version.toString())
    }
    registerFeature("r2dbcSupport") {
        usingSourceSet(sourceSets[SourceSet.MAIN_SOURCE_SET_NAME])
        capability(group.toString(), "r2dbc-support", version.toString())
    }
    registerFeature("redisSupport") {
        usingSourceSet(sourceSets[SourceSet.MAIN_SOURCE_SET_NAME])
        capability(group.toString(), "redis-support", version.toString())
    }
    registerFeature("mockSupport") {
        usingSourceSet(sourceSets[SourceSet.MAIN_SOURCE_SET_NAME])
        capability(group.toString(), "mock-support", version.toString())
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
    registerFeature("openapiSupport") {
        usingSourceSet(sourceSets[SourceSet.MAIN_SOURCE_SET_NAME])
        capability(group.toString(), "openapi-support", version.toString())
    }
    registerFeature("cosecSupport") {
        usingSourceSet(sourceSets[SourceSet.MAIN_SOURCE_SET_NAME])
        capability(group.toString(), "cosec-support", version.toString())
    }
}
dependencies {
    kapt(platform(project(":wow-dependencies")))
    api(project(":wow-core"))
    api(project(":wow-spring"))
    implementation(project(":wow-compensation-core"))
    "mongoSupportImplementation"(project(":wow-mongo"))
    "mongoSupportImplementation"("org.springframework.boot:spring-boot-starter-data-mongodb-reactive")
    "redisSupportImplementation"(project(":wow-redis"))
    "redisSupportImplementation"("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    "r2dbcSupportImplementation"("org.springframework.boot:spring-boot-starter-r2dbc")
    "r2dbcSupportImplementation"(project(":wow-r2dbc"))
    "mockSupportImplementation"(project(":wow-mock"))
    "kafkaSupportImplementation"(project(":wow-kafka"))
    "webfluxSupportImplementation"(project(":wow-webflux"))
    "cosecSupportImplementation"(project(":wow-cosec"))
    "elasticsearchSupportImplementation"(project(":wow-elasticsearch"))
    "elasticsearchSupportImplementation"("org.springframework.boot:spring-boot-starter-elasticsearch")
    "opentelemetrySupportImplementation"(project(":wow-opentelemetry"))
    "openapiSupportImplementation"(project(":wow-openapi"))
    "openapiSupportImplementation"("org.springdoc:springdoc-openapi-starter-common")
    api("org.springframework:spring-webflux")
    api("org.springframework.boot:spring-boot-starter")
    api("org.springframework.boot:spring-boot-starter-jackson")
    api("org.springframework.boot:spring-boot-starter-web")
    kapt("org.springframework.boot:spring-boot-configuration-processor")
    kapt("org.springframework.boot:spring-boot-autoconfigure-processor")
    testImplementation(project(":wow-test"))
    testImplementation(project(":wow-tck"))
    testImplementation(project(":example-domain"))
    testImplementation(project(":example-transfer-domain"))
    testImplementation(project(":wow-compensation-domain"))
    testImplementation("org.mariadb:r2dbc-mariadb")
    testImplementation(project(":wow-r2dbc"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux")
    testImplementation("org.springdoc:springdoc-openapi-starter-webflux-ui")
}
