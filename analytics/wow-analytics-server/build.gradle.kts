/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
    application
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlin.spring)
}

kotlin {
    jvmToolchain(21)
}

// The end-to-end tests run against containers (MongoDB, ClickHouse), apart from `check`, as a library's
// `integrationTest` layer does (AGENTS.md, Testing).
val integrationTest: SourceSet = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += output + compileClasspath
}
configurations["integrationTestImplementation"].extendsFrom(configurations.testImplementation.get())
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())
kotlin.target.compilations.getByName("integrationTest")
    .associateWith(kotlin.target.compilations.getByName("main"))
tasks.register<Test>("integrationTest") {
    description = "Runs container-backed integration tests."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)
    jvmArgs = listOf("-Dlogback.configurationFile=${rootProject.rootDir}/config/logback.xml")
}
tasks.jar.configure {
    exclude("application.yaml", "bootstrap.yaml")
    manifest {
        attributes(
            "Implementation-Title" to application.applicationName,
            "Implementation-Version" to archiveVersion,
        )
    }
}
distributions {
    main {
        contents {
            // The console (analytics design R5c), served by the server as static resources.
            from("${rootDir.absolutePath}/analytics/console/dist") {
                into("ui")
            }
        }
    }
}
application {
    mainClass.set("me.ahoo.wow.analytics.server.AnalyticsServerKt")
    applicationDefaultJvmArgs = listOf(
        "-Xms1G",
        "-Xmx1G",
        "-XX:MaxMetaspaceSize=256M",
        "-XX:MaxDirectMemorySize=256M",
        "-Xss1m",
        "-server",
        "-XX:+UseZGC",
        "-Xlog:gc*:file=logs/$applicationName-gc.log:time,tags:filecount=10,filesize=32M",
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=data",
        "-Dspring.config.location=file:./config/",
    )
}

dependencies {
    implementation(platform(project(":wow-dependencies")))
    implementation(project(":wow-view-store-starter"))
    implementation(project(":wow-dataset-registry-starter"))
    // The starter's CoSec support turns on with wow-cosec on the classpath.
    implementation(project(":wow-cosec"))
    implementation(project(":wow-clickhouse"))
    implementation(project(":wow-mongo"))
    implementation(project(":wow-kafka"))
    implementation(libs.cosec.spring.boot.starter)
    implementation(libs.cosec.webflux)
    implementation(libs.cosec.jwt)
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb-reactive")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation("me.ahoo.cosid:cosid-spring-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui")
    implementation("me.ahoo.cosid:cosid-spring-boot-starter")
    testImplementation(project(":wow-test"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("me.ahoo.test:fluent-assert-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    "integrationTestImplementation"(project(":wow-tck"))
    "integrationTestImplementation"("org.testcontainers:testcontainers-clickhouse")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
