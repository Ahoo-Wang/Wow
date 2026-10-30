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

description = "Wow Client: the typed command and query clients of a peer's HTTP routes, on the JDK HttpClient (D257)"

dependencies {
    // The guard of the hand-written equals (finding R5.B-13).
    testImplementation(project(":tck"))
    // Wow's contracts and its ports (decision D293): the wire grammar and the read API of :api, the peer contracts of
    // :manifest, the query protocol of :query-api, and :spi's JSON, ids, saga call port and query protocol JSON. No
    // engine: neither the node nor the query engine.
    api(project(":api"))
    api(project(":manifest"))
    api(project(":query-api"))
    api(project(":spi"))
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("io.github.oshai:kotlin-logging-jvm")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    // The test DSL classifies with the kernel's classification, the runtime's too, which takes this module's classifier.
    testImplementation(project(":test-dsl"))
}
