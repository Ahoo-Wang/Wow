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

// IndexFigures (`src/index`): what Elasticsearch's indices of the eShop catalog's products cost, the catalog server on its
// `search` profile seeded in process. A class path of its own: a node serves every model on its class path, so the
// catalog stays off the search server's.
val index: SourceSet = sourceSets.create("index")

// detekt reads src/main and src/test by default; IndexFigures' sources too.
configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
    source.from("src/index/kotlin")
}

dependencies {
    // The transfer domain and its server, as the benchmark server runs them; this server only adds Elasticsearch.
    implementation(project(":benchmark-server"))
    implementation("me.ahoo.wow:wow-elasticsearch")
    implementation("org.springframework.boot:spring-boot-elasticsearch")
    testImplementation("me.ahoo.wow:wow-test")
    "indexImplementation"(project(":eshop-catalog-server"))
    "indexImplementation"(project(":eshop-catalog-api"))
    "indexImplementation"("me.ahoo.wow:wow-api")
    "indexImplementation"("me.ahoo.wow:wow-query-api")
    "indexImplementation"("org.springframework.boot:spring-boot")
    "indexImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    "indexImplementation"("tools.jackson.core:jackson-databind")
}

// Installed beside the benchmark server, so the bench workflow's server tarball carries it and its measure job unpacks
// it where bench.sh finds it, with no step of its own.
val indexJar = tasks.register<Jar>("indexJar") {
    archiveBaseName.set("benchmark-index-figures")
    from(index.output)
}

tasks.installDist {
    destinationDir = project(":benchmark-server").layout.buildDirectory.dir("install/benchmark-search-server").get().asFile
    // IndexFigures and its class path in index/, beside the server's lib/: measure.sh's measure_index runs it from there.
    into("index") {
        from(indexJar)
        from(index.runtimeClasspath.filter { it.isFile })
    }
}
