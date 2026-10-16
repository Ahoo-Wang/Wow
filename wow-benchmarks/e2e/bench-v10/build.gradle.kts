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

// The V10 twin of :e2e-bench-9x: the same commands, events, state and rules, file for file (wow-benchmarks/e2e/README.md).
// Its log and snapshots are on MongoDB (the `mongo` profile) or PostgreSQL (`postgres`), its state stream published to
// Kafka. It depends on V10 modules only (decision D85). Never published; outside the coverage reports and the ABI
// checks. The plugin `me.ahoo.wow` cannot be applied inside the build that produces it, so this applies KSP and passes
// the plugin's options by hand, as the quickstart does.
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
apply(from = rootProject.file("example/wow-manifest-fragment.gradle.kts"))

dependencies {
    implementation(project(":kernel"))
    implementation(project(":spring-boot"))
    implementation(project(":mongo"))
    implementation(project(":postgres"))
    implementation(project(":kafka"))
    implementation("com.zaxxer:HikariCP")
    ksp(project(":compiler"))
    testImplementation(project(":test-dsl"))
}

// SharedCasesTest checks that the table of cases is the 9.x module's, byte for byte.
tasks.test {
    val nineXCases = rootProject.file(
        "wow-benchmarks/e2e/bench-9x/src/test/kotlin/me/ahoo/wow/benchmark/transfer/TransferCases.kt",
    )
    inputs.file(nineXCases).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("nineXCases")
    systemProperty("wow.benchmark.nine-x-cases", nineXCases.absolutePath)
    systemProperty("wow.benchmark.v10-cases", file("src/test/kotlin/me/ahoo/wow/benchmark/transfer/TransferCases.kt").absolutePath)
}
