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

// The benchmark's account and transfer domain on Axon Framework 4 with its JDBC event storage engine on PostgreSQL,
// no Axon Server, behind the HTTP routes the load generator speaks (test/benchmarks/README.md, the industry
// comparison). Never published. `installDist` gives the harness its launcher, `bin/axon-server`.
plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.spring") version "2.4.21"
    application
}

application {
    mainClass.set("me.ahoo.wow.benchmark.axon.AxonServerKt")
    applicationName = "axon-server"
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    implementation(platform("org.axonframework:axon-bom:4.13.3"))
    implementation("org.axonframework:axon-spring-boot-starter") {
        // The JDBC event storage engine stores the events: no Axon Server.
        exclude(group = "org.axonframework", module = "axon-server-connector")
    }
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.axonframework:axon-test")
    // axon-test declares Hamcrest optional, and its fixtures need it.
    testImplementation("org.hamcrest:hamcrest")
    // Every JUnit artifact from the repository's JUnit BOM. Spring Boot's BOM manages JUnit too, and axon-test
    // brings a newer Jupiter: without one platform over them all, the launcher could stay at Boot's version while the
    // engine moves on, and JUnit fails at its initialization.
    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // A test JUnit finds but will not run fails the run, as in the repository's build, whose rule does not reach
    // a build of its own: a discovery issue of severity WARNING, which JUnit otherwise only logs.
    systemProperty("junit.platform.discovery.issue.severity.critical", "WARNING")
}
