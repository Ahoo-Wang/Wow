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

description = "Wow end-to-end benchmark (D224): the account and transfer saga domain and its server on V10"

// The benchmark's account and transfer saga domain and its server (test/benchmarks/README.md). Its log and snapshots
// are on MongoDB (the `mongo` profile) or PostgreSQL (`postgres`), its state stream published to Kafka. It depends on
// V10 modules only (decision D85). Never published; outside the coverage reports and the ABI checks. The plugin
// `me.ahoo.wow` cannot be applied inside the build that produces it, so this applies KSP and passes the plugin's options
// by hand, as the quickstart does.
plugins {
    alias(libs.plugins.ksp)
    application
}

application {
    mainClass.set("me.ahoo.wow.benchmark.transfer.TransferServerKt")
}

ksp {
    arg("wow.model", "generate")
    arg("wow.manifest.module", project.name)
}

// The plugin's completion of the fragment with the schemas of the types, so the snapshot queries know the state.
apply(from = rootProject.file("examples/wow-manifest-fragment.gradle.kts"))

dependencies {
    implementation(project(":kernel"))
    implementation(project(":spring-boot"))
    implementation(project(":mongo"))
    implementation(project(":postgres"))
    implementation(project(":kafka"))
    // The pool the host makes of `spring.datasource.*` in the postgres profile (decision D310).
    runtimeOnly("com.zaxxer:HikariCP")
    // The node's health contributors, without Actuator's endpoints: BenchQueryLimits reads the stores' query bounds
    // from the `wow` contributor, as an operator reads them.
    implementation("org.springframework.boot:spring-boot-health")
    ksp(project(":compiler"))
    testImplementation(project(":test-dsl"))
}

tasks.test {
    // BenchSignalsCopyTest checks that the eShop soak's copy of BenchSignals is the same class.
    val soakSignals = rootProject.file(
        "examples/eshop/e2e/src/soakTest/kotlin/me/ahoo/wow/example/eshop/e2e/soak/SoakSignals.kt",
    )
    inputs.file(soakSignals).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("soakSignals")
    systemProperty("wow.benchmark.soak-signals", soakSignals.absolutePath)
    systemProperty("wow.benchmark.bench-signals", file("src/main/kotlin/me/ahoo/wow/benchmark/transfer/BenchSignals.kt").absolutePath)
}
