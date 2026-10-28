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

description = "Wow SPI consumer: a third-party store on the public SPI alone, run through StoreTck"

// A store as a backend outside Wow writes it, in a project that opts in to nothing (`userProjects`): if the store
// SPI it implements or the query facet it gives reached an internal or experimental type, this would not compile.
// It holds snapshots only. The kernel's in-memory ports are internal, so a log written here would be a backend's worth
// of code; the log SPI's public type closure is the ABI-closure rule's to check. So StoreTck's log specs skip here by
// design, not as a gap. Never published; outside the ABI checks.
dependencies {
    implementation(project(":spi"))
    implementation(project(":query"))
    testImplementation(project(":tck"))
    testImplementation(project(":query-tck"))
}
