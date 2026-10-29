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

description = "Wow migration rehearsal (roadmap 8c.1): the 9.x example ported to V10, the V10 side of the blue-green cut-over"

// The 9.x example (example-server on v10-9x-baseline, D262) ported to V10 for the blue-green rehearsal only: its
// order and cart, under the names migration-map.json gives them, and the CartSaga subscription that parks the 9.x
// compensation service's unresolved executions. rehearse.sh imports the 9.x data with migrate-data, switches the
// traffic to this host and rolls it back (decision D32). Never published; outside the coverage reports and the ABI
// checks. The plugin `me.ahoo.wow` builds its models; it has no Manifest gate, being no service a client depends on.
plugins {
    application
    id("me.ahoo.wow")
}

application {
    mainClass.set("me.ahoo.wow.rehearsal.RehearsalServerKt")
}

dependencies {
    implementation("me.ahoo.wow:wow-spring-boot")
    // The CallerResolver that names the driver's tenant and owner.
    implementation("me.ahoo.wow:wow-http")
    implementation("me.ahoo.wow:wow-postgres")
    // The pool the host makes of `spring.datasource.*` (decision D310).
    runtimeOnly("com.zaxxer:HikariCP")
    // Actuator's health endpoint, which rehearse.sh waits on.
    implementation("org.springframework.boot:spring-boot-health")
    implementation("org.springframework.boot:spring-boot-actuator-autoconfigure")
    testImplementation("me.ahoo.wow:wow-test")
}
