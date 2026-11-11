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

description = "Wow end-to-end benchmark: the benchmark server with an Elasticsearch store, for the measurements of the search and the sink"

// The transfer domain comes built from :benchmark-server, so this server applies no `me.ahoo.wow` plugin. Never
// published; outside the coverage reports and the ABI checks.
plugins {
    application
}

application {
    mainClass.set("me.ahoo.wow.benchmark.search.SearchServerKt")
}

dependencies {
    // The transfer domain and its server, as the benchmark server runs them; this server only adds Elasticsearch.
    implementation(project(":benchmark-server"))
    implementation("me.ahoo.wow:wow-elasticsearch")
    implementation("org.springframework.boot:spring-boot-elasticsearch")
    testImplementation("me.ahoo.wow:wow-test")
}

// Installed beside the benchmark server, so the bench workflow's server tarball carries it and its measure job unpacks
// it where bench.sh finds it, with no step of its own.
tasks.installDist {
    destinationDir = project(":benchmark-server").layout.buildDirectory.dir("install/benchmark-search-server").get().asFile
}
