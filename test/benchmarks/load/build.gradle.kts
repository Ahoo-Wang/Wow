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

description = "Wow end-to-end benchmark (D224): the load generator, a JVM of its own on the JDK alone"

// It depends on no Wow module and no library beyond the Kotlin standard library: the JDK's HttpClient sends, its own
// log-linear histogram records (test/benchmarks/README.md). Never published; outside the coverage reports and the
// ABI checks. `installDist` gives the harness its launcher.
plugins {
    application
}

application {
    mainClass.set("me.ahoo.wow.benchmark.load.LoadMainKt")
    applicationName = "benchmark-load"
}
