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

rootProject.name = "Wow"

include(":wow-bom")
include(":wow-dependencies")
include(":wow-api")
include(":wow-core")
include(":wow-compiler")
include(":wow-kafka")
include(":wow-mongo")
include(":wow-r2dbc")
include(":wow-elasticsearch")
include(":wow-spring")
include(":wow-webflux")
include(":wow-spring-boot-starter")
include(":wow-opentelemetry")

//region test
include(":wow-test")
project(":wow-test").projectDir = file("test/wow-test")
include(":wow-tck")
project(":wow-tck").projectDir = file("test/wow-tck")
include(":wow-it")
project(":wow-it").projectDir = file("test/wow-it")
include(":code-coverage-report")
project(":code-coverage-report").projectDir = file("test/code-coverage-report")
//endregion
//region example
include(":example-api")
project(":example-api").projectDir = file("example/example-api")

include(":example-domain")
project(":example-domain").projectDir = file("example/example-domain")

include(":example-server")
project(":example-server").projectDir = file("example/example-server")
//endregion
pluginManagement {
    plugins {
        id("com.google.devtools.ksp") version "1.8.21-1.0.11" apply false
        id("io.gitlab.arturbosch.detekt") version "1.22.0" apply false
        kotlin("jvm") version "1.8.21" apply false
        kotlin("plugin.spring") version "1.8.21" apply false
        id("org.jetbrains.dokka") version "1.8.10" apply false
        id("io.github.gradle-nexus.publish-plugin") version "1.3.0" apply false
    }
}

