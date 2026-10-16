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

description = "Wow end-to-end benchmark (D224): the account and transfer saga domain and its server on 9.x"

// The 9.x twin of :e2e-bench-v10: the same commands, events, state and rules, file for file (wow-benchmarks/e2e/README.md).
// It runs in its 9.x production topology, events and snapshots on MongoDB and commands and events on Kafka, and
// depends on 9.x modules only (decision D85). Never published; outside the coverage reports and the ABI checks.
plugins {
    alias(libs.plugins.ksp)
    application
}

application {
    mainClass.set("me.ahoo.wow.benchmark.transfer.TransferServerKt")
}

dependencies {
    ksp(project(":wow-compiler"))
    implementation(project(":wow-spring-boot-starter"))
    implementation(project(":wow-webflux"))
    implementation(project(":wow-mongo"))
    implementation(project(":wow-kafka"))
    implementation("me.ahoo.cosid:cosid-spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb-reactive")
    // CosId's starter registers health indicators.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // The command gateway validates commands with the application's Bean Validation provider, as the 9.x examples do.
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("io.github.oshai:kotlin-logging-jvm")
    testImplementation(project(":wow-test"))
    testImplementation("io.projectreactor:reactor-test")
}
