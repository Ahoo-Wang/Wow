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

/*
 * The platform every published Wow module imports (R5.C-4, D287): the versions of the libraries the Wow runtime itself
 * puts on an application's class path, and nothing else. The contract modules (wow-annotations, wow-api,
 * wow-query-api, wow-manifest) do not import it: a domain module that depends on them alone keeps its own versions of
 * everything, Jackson included. The Spring Boot BOM goes with wow-spring-boot alone; the test and lint
 * platforms (Testcontainers, springdoc, fluent-assert, MockK, detekt) are the repository build's (`wowBuildPlatform`
 * in the root build), never published.
 *
 * Jackson and SLF4J take the versions the build's Spring Boot manages, read from its BOM, so the platform and what
 * Wow is built and tested with cannot drift apart.
 */
val springBootManaged: List<String> = listOf(
    "tools.jackson.core:jackson-core",
    "tools.jackson.core:jackson-databind",
    "tools.jackson.module:jackson-module-kotlin",
    "com.fasterxml.jackson.core:jackson-annotations",
    "org.slf4j:slf4j-api",
)
val managedVersions: Map<String, String> by lazy {
    val resolution = configurations.detachedConfiguration(
        project.dependencies.platform(libs.spring.boot.dependencies.get().toString()),
        *springBootManaged.map { project.dependencies.create(it) }.toTypedArray(),
    )
    resolution.incoming.resolutionResult.allComponents
        .mapNotNull { it.moduleVersion }
        .associate { "${it.group}:${it.name}" to it.version }
}

dependencies {
    api(platform(libs.kotlinx.coroutines.bom))
    constraints {
        springBootManaged.forEach { module -> api("$module:${managedVersions.getValue(module)}") }
        api(libs.kotlin.logging)
    }
}
