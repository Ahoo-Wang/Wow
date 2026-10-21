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
 * Only V10 projects remain: the 9.x code left early (decisions D244, D262; it lives on at the side branch
 * `v10-9x-baseline` and on `v9`). The build scope `-Pwow.scope` that told the two apart is gone.
 */

/*
 * `-Pwow.publication.prebuilt=true`: publish the files an earlier build made instead of building them. A release's
 * deploy jobs (package-deploy.yml) download what the preflight built, signed and checked (`build/libs`,
 * `build/publications`) and publish it with this property, which leaves every task that makes a published file out of
 * the task graph, as `-x` would. The Sign tasks are left out too: the deploy jobs have no key, and the publications
 * upload the preflight's signatures, which the V10 signing configuration then requires (build.gradle.kts).
 * `publicationCheck` (build.gradle.kts) runs such a publish and checks that it runs nothing but the publish tasks
 * and uploads every file, signed, so a published file no listed task makes, or a missing signature, fails a pull
 * request, not a release.
 */
val prebuiltPublicationTasks = listOf("jar", "sourcesJar", "javadocJar") +
    listOf("MavenLibrary", "MavenBom", "PluginMaven", "WowPluginMarkerMaven").flatMap { publication ->
        listOf(
            "generatePomFileFor${publication}Publication",
            "generateMetadataFileFor${publication}Publication",
            "sign${publication}Publication",
        )
    }
gradle.extensions.extraProperties["wowPublicationPrebuiltTasks"] = prebuiltPublicationTasks
if (providers.gradleProperty("wow.publication.prebuilt").orNull.toBoolean()) {
    gradle.startParameter.setExcludedTaskNames(gradle.startParameter.excludedTaskNames + prebuiltPublicationTasks)
}

include(":wow-dependencies")
include(":code-coverage-report")
project(":code-coverage-report").projectDir = file("test/code-coverage-report")

//region wow (documentation/designs/v10/06-modules.md)
// The V10 BOM (artifact wow-bom, D87; release configuration R.1).
include(":bom")
project(":bom").projectDir = file("bom/bom")
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
// Snapshots, their queries and event search on Elasticsearch, no log (decisions D13, D167; work package 6.7).
include(":elasticsearch")
project(":elasticsearch").projectDir = file("backends/elasticsearch")
include(":query")
project(":query").projectDir = file("query/query")
// The query backend contract specs (review R3.3-2), published so that a backend outside Wow runs them.
include(":query-tck")
project(":query-tck").projectDir = file("query/query-tck")
include(":openapi")
project(":openapi").projectDir = file("edge/openapi")
include(":http")
project(":http").projectDir = file("edge/http")
// The typed command and query clients over HTTP (decisions D257, D259): JDK HttpClient only, no Spring.
include(":client")
project(":client").projectDir = file("edge/client")
include(":cosec")
project(":cosec").projectDir = file("edge/cosec")
// Tracing and metrics on the core's observation seams (decision D174); artifact wow-opentelemetry.
include(":opentelemetry")
project(":opentelemetry").projectDir = file("edge/opentelemetry")
include(":spring-boot")
project(":spring-boot").projectDir = file("host/spring-boot")
// Optional extensions an application adds beside the host (decision D171: CosId's id generators).
include(":cosid")
project(":cosid").projectDir = file("extensions/cosid")
// 9.x wow-models' capabilities and reusable Tree aggregate, ported (decision D187, P.8); artifact wow-models.
include(":models")
project(":models").projectDir = file("extensions/models")
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
include(":ops-server")
project(":ops-server").projectDir = file("platform/ops/ops-server")
// The view store (roadmap 7.2a): the view engine's saved views, view preferences and dataset wording, ported from the
// 9.x view-store/ (decision D146) with the migration Skill.
include(":view-store-api")
project(":view-store-api").projectDir = file("platform/view-store/view-store-api")
include(":view-store-domain")
project(":view-store-domain").projectDir = file("platform/view-store/view-store-domain")
include(":view-store-server")
project(":view-store-server").projectDir = file("platform/view-store/view-store-server")
// The analytics catalog (roadmap 9.3b, decisions D238, D241, D251): the control plane of the analytics platform, the
// warehouse's data sources, their trust and review, and the wide tables built on them; in place of the 9.x
// dataset-registry/.
include(":analytics-catalog-api")
project(":analytics-catalog-api").projectDir = file("platform/analytics/catalog/api")
include(":analytics-catalog-domain")
project(":analytics-catalog-domain").projectDir = file("platform/analytics/catalog/domain")
include(":analytics-catalog-server")
project(":analytics-catalog-server").projectDir = file("platform/analytics/catalog/server")
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
// each an API, a domain and a server of its own, and their end-to-end tests. Every project is named `eshop-*`.
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
// The V10 quickstart (decision D217): the sample the quickstart page shows, one module, its domain and its server.
include(":quickstart")
project(":quickstart").projectDir = file("example/quickstart")
include(":java-smoke")
project(":java-smoke").projectDir = file("example/java-smoke")
// The V10 side of the end-to-end architecture benchmark (decision D224, roadmap M7.5 B.0): the twin benchmark domain
// and its server on V10 modules only. Never published.
include(":benchmark-server")
project(":benchmark-server").projectDir = file("test/benchmarks/server")
//endregion

// The load generator of the end-to-end architecture benchmark (decision D224): a JVM of its own that depends on no Wow
// module, so it drives the 9.x (built from `v10-9x-baseline`, D262) and the V10 servers alike over HTTP.
include(":benchmark-load")
project(":benchmark-load").projectDir = file("test/benchmarks/load")
