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

description = "Wow end-to-end benchmark: the benchmark server with no-op projections of the account, for the cost of a subscription"

// The transfer domain comes built from :benchmark-server, so this server applies no `me.ahoo.wow` plugin: its
// projections are bound by a hand-written ModelProvider, so that their number is a property rather than a build.
// Never published; outside the coverage reports and the ABI checks.
plugins {
    application
}

application {
    mainClass.set("me.ahoo.wow.benchmark.subscriptions.SubscriptionsServerKt")
}

dependencies {
    // The transfer domain and its server, as the benchmark server runs them; this server only adds projections.
    implementation(project(":benchmark-server"))
    implementation("me.ahoo.wow:wow-kernel")
    implementation("me.ahoo.wow:wow-spring-boot")
    testImplementation("me.ahoo.wow:wow-test")
    testImplementation("me.ahoo.wow:wow-test-spring-boot")
}

// Installed beside the benchmark server, as the search server is; bench.sh runs it with --install.
tasks.installDist {
    destinationDir =
        project(":benchmark-server").layout.buildDirectory.dir("install/benchmark-subscriptions-server").get().asFile
}
