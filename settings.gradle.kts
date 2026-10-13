pluginManagement {
    includeBuild("build-logic")
}

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

/*
 * The build scope, `-Pwow.scope=<scope>` (or `wow.scope=<scope>` in ~/.gradle/gradle.properties):
 * - `all` (the default): every project, 9.x and V10.
 * - `v10`: only the V10 projects (the `//region v10` below), the `:wow-dependencies` platform they import and
 *   `:code-coverage-report` for their coverage; no 9.x module is configured or built. `:kernel-benchmarks` (spike H3)
 *   is left out: it runs the 9.x kernel beside the V10 one (decision D90), so it would bring in `:wow-core`,
 *   `:example-domain`, `:wow-benchmarks` and most of 9.x with them. The full build compiles it.
 * The root build's `v10Check` and `v10IntegrationTest` run in either scope.
 */
val wowScope = providers.gradleProperty("wow.scope").getOrElse("all")
require(wowScope in setOf("all", "v10")) { "Unknown wow.scope '$wowScope': use 'all' or 'v10'." }
val nineX = wowScope == "all"

include(":wow-dependencies")
include(":code-coverage-report")
project(":code-coverage-report").projectDir = file("test/code-coverage-report")

if (nineX) {
    include(":wow-bom")
    include(":wow-api")
    include(":wow-metadata")
    include(":wow-rest-contract")
    include(":wow-core")
    include(":wow-openapi")
    include(":wow-cocache")
    include(":wow-apiclient")
    include(":wow-compiler")
    include(":wow-kafka")
    include(":wow-mongo")
    include(":wow-redis")
    include(":wow-elasticsearch")
    include(":wow-spring")
    include(":wow-webflux")
    include(":wow-spring-boot-starter")
    include(":wow-opentelemetry")
    include(":wow-bi")
    include(":wow-clickhouse")
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
    //region view-store
    include(":wow-view-store-api")
    project(":wow-view-store-api").projectDir = file("view-store/wow-view-store-api")
    include(":wow-view-store-domain")
    project(":wow-view-store-domain").projectDir = file("view-store/wow-view-store-domain")
    include(":wow-view-store-starter")
    project(":wow-view-store-starter").projectDir = file("view-store/wow-view-store-starter")
    include(":wow-view-store-server")
    project(":wow-view-store-server").projectDir = file("view-store/wow-view-store-server")
    //endregion
    //region dataset-registry
    include(":wow-dataset-registry-api")
    project(":wow-dataset-registry-api").projectDir = file("dataset-registry/wow-dataset-registry-api")
    include(":wow-dataset-registry-domain")
    project(":wow-dataset-registry-domain").projectDir = file("dataset-registry/wow-dataset-registry-domain")
    include(":wow-dataset-registry-starter")
    project(":wow-dataset-registry-starter").projectDir = file("dataset-registry/wow-dataset-registry-starter")
    //endregion

    //region analytics
    include(":wow-analytics-server")
    project(":wow-analytics-server").projectDir = file("analytics/wow-analytics-server")
    //endregion
}

//region v10 (documentation/designs/v10/06-modules.md)
include(":api")
project(":api").projectDir = file("api/api")
include(":manifest")
project(":manifest").projectDir = file("api/manifest")
include(":query-api")
project(":query-api").projectDir = file("api/query-api")
include(":spi")
project(":spi").projectDir = file("core/spi")
include(":kernel")
project(":kernel").projectDir = file("core/kernel")
include(":runtime")
project(":runtime").projectDir = file("core/runtime")
include(":reactor")
project(":reactor").projectDir = file("core/reactor")
include(":postgres")
project(":postgres").projectDir = file("backends/postgres")
include(":mongo")
project(":mongo").projectDir = file("backends/mongo")
include(":kafka")
project(":kafka").projectDir = file("backends/kafka")
include(":clickhouse")
project(":clickhouse").projectDir = file("backends/clickhouse")
include(":query")
project(":query").projectDir = file("query/query")
include(":http")
project(":http").projectDir = file("edge/http")
include(":cosec")
project(":cosec").projectDir = file("edge/cosec")
include(":spring-boot")
project(":spring-boot").projectDir = file("host/spring-boot")
include(":migrate-data")
project(":migrate-data").projectDir = file("migration/migrate-data")
include(":architecture")
project(":architecture").projectDir = file("test/architecture")
if (nineX) {
    include(":kernel-benchmarks")
    project(":kernel-benchmarks").projectDir = file("test/kernel-benchmarks")
}
include(":compiler")
project(":compiler").projectDir = file("compiler/compiler")
include(":gradle-plugin")
project(":gradle-plugin").projectDir = file("compiler/gradle-plugin")
include(":manifest-tools")
project(":manifest-tools").projectDir = file("compiler/manifest-tools")
include(":routing-spike")
project(":routing-spike").projectDir = file("test/routing-spike")
include(":lease-spike")
project(":lease-spike").projectDir = file("test/lease-spike")
// The V10 example (decision D111); the 9.x example under example/ stays as it is until M9.
include(":shop-api")
project(":shop-api").projectDir = file("example/shop/shop-api")
include(":shop-domain")
project(":shop-domain").projectDir = file("example/shop/shop-domain")
include(":shop-server")
project(":shop-server").projectDir = file("example/shop/shop-server")
// The example's end-to-end tests across its services (decision D144): test-only, never published.
include(":example-e2e")
project(":example-e2e").projectDir = file("example/e2e")
//endregion
