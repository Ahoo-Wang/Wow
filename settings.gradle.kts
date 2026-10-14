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
// Optional extensions an application adds beside the host (decision D171: CosId's id generators).
include(":cosid")
project(":cosid").projectDir = file("extensions/cosid")
include(":migrate-data")
project(":migrate-data").projectDir = file("migration/migrate-data")
include(":architecture")
project(":architecture").projectDir = file("test/architecture")
// The test modules, the gate of every iteration (decision D196): the test DSL (artifact wow-test; `:test` would clash
// with the `test` task, D87), the SPI contract specs (wow-tck) and the containers the backends run them against.
include(":test-dsl")
project(":test-dsl").projectDir = file("test/test-dsl")
include(":tck")
project(":tck").projectDir = file("test/tck")
include(":tck-containers")
project(":tck-containers").projectDir = file("test/tck-containers")
// The platform (roadmap M7): the ops context, the aggregation and operation surface of every service's failure ledger
// (decision D141); work package 6.3f gives it its API and the parked aggregate, 7.1a its service.
include(":ops-api")
project(":ops-api").projectDir = file("platform/ops/ops-api")
include(":ops-domain")
project(":ops-domain").projectDir = file("platform/ops/ops-domain")
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
// The V10 example, the eShop (decisions D158, D159, D204): five contexts after the classic eShop reference application,
// each an API, a domain and a server of its own, and their end-to-end tests; the 9.x example under example/ stays as it
// is until M9. Every project is named `eshop-*`, so none clashes with a 9.x example's.
include(":eshop-catalog-api")
project(":eshop-catalog-api").projectDir = file("example/eshop/catalog/catalog-api")
include(":eshop-catalog-domain")
project(":eshop-catalog-domain").projectDir = file("example/eshop/catalog/catalog-domain")
include(":eshop-catalog-server")
project(":eshop-catalog-server").projectDir = file("example/eshop/catalog/catalog-server")
include(":eshop-basket-api")
project(":eshop-basket-api").projectDir = file("example/eshop/basket/basket-api")
include(":eshop-basket-domain")
project(":eshop-basket-domain").projectDir = file("example/eshop/basket/basket-domain")
include(":eshop-basket-server")
project(":eshop-basket-server").projectDir = file("example/eshop/basket/basket-server")
include(":eshop-ordering-api")
project(":eshop-ordering-api").projectDir = file("example/eshop/ordering/ordering-api")
include(":eshop-ordering-domain")
project(":eshop-ordering-domain").projectDir = file("example/eshop/ordering/ordering-domain")
include(":eshop-ordering-server")
project(":eshop-ordering-server").projectDir = file("example/eshop/ordering/ordering-server")
include(":eshop-inventory-api")
project(":eshop-inventory-api").projectDir = file("example/eshop/inventory/inventory-api")
include(":eshop-inventory-domain")
project(":eshop-inventory-domain").projectDir = file("example/eshop/inventory/inventory-domain")
include(":eshop-inventory-server")
project(":eshop-inventory-server").projectDir = file("example/eshop/inventory/inventory-server")
include(":eshop-payment-api")
project(":eshop-payment-api").projectDir = file("example/eshop/payment/payment-api")
include(":eshop-payment-domain")
project(":eshop-payment-domain").projectDir = file("example/eshop/payment/payment-domain")
include(":eshop-payment-server")
project(":eshop-payment-server").projectDir = file("example/eshop/payment/payment-server")
include(":eshop-e2e")
project(":eshop-e2e").projectDir = file("example/eshop/e2e")
//endregion
