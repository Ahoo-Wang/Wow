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

description = "Wow OpenTelemetry: tracing and Micrometer metrics on the core's observation seams (decision D174)"

dependencies {
    api(project(":runtime"))
    // The query observer of the query engine (decision D126).
    api(project(":query"))
    // The OpenTelemetry API and Micrometer only: the application (or the host's Spring Boot) brings the SDK, the
    // exporters and the meter registry.
    api("io.opentelemetry:opentelemetry-api")
    api("io.micrometer:micrometer-core")
    implementation("io.github.oshai:kotlin-logging-jvm")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation(testFixtures(project(":manifest")))
    // The end-to-end trace from an HTTP request: the routes of wow-http, served through WebTestClient.
    testImplementation(project(":http"))
    testImplementation("org.springframework:spring-test")
    testImplementation("io.projectreactor:reactor-test")
    // The default handler strategies of a router function served without Spring Boot read the locale context.
    testImplementation("org.springframework:spring-context")
    // The in-memory reference query backend, behind a query gateway.
    testImplementation(project(":query-tck"))
}
