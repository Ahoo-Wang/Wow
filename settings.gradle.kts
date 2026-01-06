plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
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
include(":wow-openapi")
include(":wow-cocache")
include(":wow-apiclient")
include(":wow-compiler")
include(":wow-kafka")
include(":wow-mongo")
include(":wow-r2dbc")
include(":wow-redis")
include(":wow-elasticsearch")
include(":wow-spring")
include(":wow-webflux")
include(":wow-spring-boot-starter")
include(":wow-opentelemetry")
include(":wow-bi")
include(":wow-query")
include(":wow-models")
include(":wow-schema")
include(":wow-cosec")
include(":wow-benchmarks")

//region test
include(":wow-test")
project(":wow-test").projectDir = file("test/wow-test")
include(":wow-tck")
project(":wow-tck").projectDir = file("test/wow-tck")
include(":wow-mock")
project(":wow-mock").projectDir = file("test/wow-mock")
include(":wow-it")
project(":wow-it").projectDir = file("test/wow-it")
include(":code-coverage-report")
project(":code-coverage-report").projectDir = file("test/code-coverage-report")
//endregion
//region compensation
include(":wow-compensation-api")
project(":wow-compensation-api").projectDir = file("compensation/wow-compensation-api")
include(":wow-compensation-core")
project(":wow-compensation-core").projectDir = file("compensation/wow-compensation-core")
include(":wow-compensation-domain")
project(":wow-compensation-domain").projectDir = file("compensation/wow-compensation-domain")
include(":wow-compensation-server")
project(":wow-compensation-server").projectDir = file("compensation/wow-compensation-server")
//endregion
//region example
include(":example-api")
project(":example-api").projectDir = file("example/example-api")

include(":example-domain")
project(":example-domain").projectDir = file("example/example-domain")

include(":example-server")
project(":example-server").projectDir = file("example/example-server")

include("example-transfer-api")
project(":example-transfer-api").projectDir = file("example/transfer/example-transfer-api")

include("example-transfer-domain")
project(":example-transfer-domain").projectDir = file("example/transfer/example-transfer-domain")

include("example-transfer-server")
project(":example-transfer-server").projectDir = file("example/transfer/example-transfer-server")
//endregion
