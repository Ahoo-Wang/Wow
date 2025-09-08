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
    alias(libs.plugins.ksp)
    application
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlin.spring)
    kotlin("kapt")
}

kotlin {
    jvmToolchain(17)
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
            val dashboardDistPath = "${rootDir.absolutePath}/compensation/dashboard/dist"
            from(dashboardDistPath).include("**")
        }
    }
}
application {
    mainClass.set("me.ahoo.wow.compensation.server.CompensationServerKt")
    applicationDefaultJvmArgs = listOf(
        "-Xms1792M",
        "-Xmx1792M",
        "-XX:MaxMetaspaceSize=256M",
        "-XX:MaxDirectMemorySize=512M",
        "-Xss1m",
        "-server",
        "-XX:+UseZGC",
        "-Xlog:gc*:file=logs/$applicationName-gc.log:time,tags:filecount=10,filesize=32M",
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=data",
        "-Dcom.sun.management.jmxremote",
        "-Dcom.sun.management.jmxremote.authenticate=false",
        "-Dcom.sun.management.jmxremote.ssl=false",
        "-Dcom.sun.management.jmxremote.port=5555",
        "-Dspring.cloud.bootstrap.enabled=true",
        "-Dspring.cloud.bootstrap.location=config/bootstrap.yaml",
        "-Dspring.config.location=file:./config/",
    )
}

dependencies {
    implementation(platform(project(":wow-dependencies")))
    kapt(platform(project(":wow-dependencies")))
    ksp(project(":wow-compiler"))
    implementation("io.netty:netty-all")
    implementation(project(":wow-compensation-domain"))
    implementation(project(":wow-opentelemetry"))
    implementation(project(":wow-webflux"))
    implementation(project(":wow-cosec"))
    implementation(project(":wow-spring-boot-starter"))
    implementation(project(":wow-kafka"))
    implementation(project(":wow-mongo"))
    implementation(project(":wow-elasticsearch"))
    implementation(project(":wow-redis"))
    implementation(libs.jte)
    implementation(libs.jte.kotlin)
    implementation("me.ahoo.cosid:cosid-mongo")
    implementation("me.ahoo.cosid:cosid-spring-redis")
    implementation("me.ahoo.cosid:cosid-spring-boot-starter")
    implementation("me.ahoo.simba:simba-spring-redis")
    implementation("me.ahoo.simba:simba-spring-boot-starter")
    implementation("me.ahoo.coapi:coapi-spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb-reactive")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    api("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    kapt("org.springframework.boot:spring-boot-configuration-processor")
    testImplementation(project(":wow-test"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.mockk:mockk") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
