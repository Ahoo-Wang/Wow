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

package me.ahoo.wow.analytics.catalog.server

import com.clickhouse.client.api.Client
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import me.ahoo.wow.analytics.catalog.domain.dataset.DatasetPolicy
import me.ahoo.wow.analytics.catalog.domain.dataset.DatasetSources
import me.ahoo.wow.analytics.catalog.domain.dataset.DatasetState
import me.ahoo.wow.analytics.catalog.domain.dataset.DatasetWarehouse
import me.ahoo.wow.analytics.catalog.domain.warehouse.KnownAggregates
import me.ahoo.wow.analytics.catalog.domain.warehouse.TrustedWriters
import me.ahoo.wow.analytics.catalog.domain.warehouse.WarehouseCatalog
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.api.read.snapshots
import me.ahoo.wow.clickhouse.ClickHouseClients
import me.ahoo.wow.client.WowClient
import me.ahoo.wow.cosec.CoSecCommandAuthorizer
import me.ahoo.wow.query.dataset.QueryDatasets
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver
import me.ahoo.wow.spring.boot.SignedInCallerResolver
import me.ahoo.wow.spring.boot.WowProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Instant
import kotlin.time.toKotlinDuration

/**
 * The analytics catalog's beans: who calls it and what each caller may send or read, and the catalog's ports.
 *
 * - Who calls it: CoSec's callers, from the token the gateway verified and forwarded (the `cosec` profile, CoSec's
 *   inject mode); in development without CoSec, the host's, from the `Wow-*` identity headers any client sets
 *   (`DevelopmentCallerResolver`), of which a request without a user is no caller here ([requireSignedInCaller]): it is
 *   answered 401, as behind the gateway. A node outside development without CoSec does not start.
 * - What each may send: [AnalyticsCatalogAuthorizer], by role, or CoSec's policies when CoSec authorizes the requests
 *   itself and `wow.cosec.authorizer.enabled` is on; what each may read: [AnalyticsCatalogQueryPolicy].
 * - The ClickHouse client: one client of `wow.clickhouse.endpoint` and its credentials ([ClickHouseClients]) the
 *   catalog, its datasets' warehouse, and the host's Sink and dataset backend share, closed with the context.
 * - The catalog: the [WarehouseCatalog] of the ClickHouse server that client reaches, over
 *   `wow.analytics.catalog.databases` (`wow.clickhouse.database` when none), or [WarehouseCatalog.NONE] without one; the [TrustedWriters] of `wow.analytics.catalog.trusted-writers`; the
 *   [KnownAggregates] of the sources' snapshots; the [DatasetSources] a dataset's definition is checked against,
 *   the sources' latest states; and the [CatalogWatcher] that syncs what changed every
 *   `wow.analytics.catalog.refresh-interval`, unless `wow.analytics.catalog.watch.enabled=false`.
 * - The datasets (-2): the [DatasetWarehouse] their versions' views are created in, on the same
 *   ClickHouse server ([DatasetWarehouse.NONE] without one), the [DatasetPolicy] of `wow.analytics.catalog.datasets.*`,
 *   and, unless `wow.analytics.catalog.datasets.serve=false`, the [DatasetPublicationWatcher] that serves the published
 *   versions on this node, with its readiness (`datasetPublications`, out of service until its first read) and its
 *   metrics (`wow.analytics.catalog.datasets.sync.age`, `wow.analytics.catalog.datasets.publication.lag`, seconds).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnalyticsCatalogProperties::class)
class AnalyticsCatalogConfiguration {
    /**
     * Who sends what: steps aside when CoSec decides ([CoSecCommandAuthorizer], its policies alone, this one then having
     * no objection); otherwise the catalog's roles decide ([AnalyticsCatalogAuthorizer]).
     */
    @Bean
    fun analyticsCatalogAuthorizer(cosec: ObjectProvider<CoSecCommandAuthorizer>): CommandAuthorizer =
        if (cosec.ifAvailable == null) AnalyticsCatalogAuthorizer() else CommandAuthorizer.ALLOW_ALL

    @Bean
    fun analyticsCatalogQueryPolicy(): QueryPolicy = AnalyticsCatalogQueryPolicy()

    /**
     * The service's one ClickHouse client, of `wow.clickhouse.*` as the Sink and the dataset backend read it: they use
     * it rather than each opening its own, and never close it; the context closes it.
     */
    // region shared-client
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = ClickHouseClients.PREFIX, name = ["endpoint"])
    fun analyticsCatalogClickHouseClient(environment: Environment): Client {
        val settings = Binder.get(environment)
            .bind(ClickHouseClients.PREFIX, Bindable.mapOf(String::class.java, String::class.java))
            .orElse(emptyMap())
            .orEmpty()
        return ClickHouseClients.of(settings)
    }
    // endregion

    /** The warehouse catalog: over `databases`, else `wow.clickhouse.database`, its one reader. */
    @Bean
    fun warehouseCatalog(
        client: ObjectProvider<Client>,
        properties: AnalyticsCatalogProperties,
        environment: Environment,
    ): WarehouseCatalog {
        val warehouse = client.ifAvailable ?: return WarehouseCatalog.NONE.also {
            logger.warn { "No ClickHouse client (wow.clickhouse.endpoint): the catalog has no warehouse table." }
        }
        val databases = properties.databases.filter { it.isNotBlank() }.ifEmpty {
            listOf(
                clickHouseSettings(environment)[ClickHouseClients.DATABASE.key]?.takeIf { it.isNotBlank() }
                    ?: ClickHouseClients.DATABASE.default,
            )
        }
        require(properties.engine == ClickHouseWarehouseCatalog.ENGINE) {
            "${AnalyticsCatalogProperties.PREFIX}.engine is ${ClickHouseWarehouseCatalog.ENGINE}, not ${properties.engine}: " +
                "the one warehouse engine 10.0 implements."
        }
        return ClickHouseWarehouseCatalog(warehouse, properties.connection, databases)
    }

    @Bean
    fun trustedWriters(properties: AnalyticsCatalogProperties): TrustedWriters =
        TrustedWriters.of(properties.trustedWriters)

    @Bean
    fun knownAggregates(queries: ObjectProvider<QueryApi>): KnownAggregates =
        QueriedKnownAggregates { queries.getObject() }

    @Bean
    fun datasetSources(queries: ObjectProvider<QueryApi>): DatasetSources =
        QueriedDatasetSources { queries.getObject() }

    /**
     * The reference actions of the datasets' rows: the commands of the peers
     * `wow.peers` describes by their Manifest (`contract: manifest`), read with the service's [WowClient]
     * in `wow.peer.mode`, and sent as the caller through the node's [CommandClient], each context in its own mode:
     * the context's service routes, the edge mounts them from the Manifest.
     */
    @Bean
    @OptIn(InternalWowApi::class)
    fun datasetReferenceActions(
        queries: ObjectProvider<QueryApi>,
        client: ObjectProvider<WowClient>,
        wow: WowProperties,
    ): DatasetReferenceActions = DatasetReferenceActions(
        datasets = { id -> queries.getObject().trusted().snapshots<DatasetState>().state(id)?.state },
        manifests = { context, caller -> client.getObject().manifestOf(context, caller) },
        described = manifestPeers(wow),
    )

    /** The peer contexts `wow.peers.<context>.contract: manifest` describes by their Manifest alone, the host's. */
    @OptIn(InternalWowApi::class)
    private fun manifestPeers(wow: WowProperties): Set<String> =
        wow.peers.filterValues { it.contract == WowProperties.PeerTarget.Contract.MANIFEST }.keys

    @Bean
    fun datasetWarehouse(client: ObjectProvider<Client>): DatasetWarehouse =
        client.ifAvailable?.let(::ClickHouseDatasetWarehouse) ?: DatasetWarehouse.NONE

    @Bean
    fun datasetPolicy(properties: AnalyticsCatalogProperties): DatasetPolicy =
        DatasetPolicy(
            database = properties.datasets.database,
            retention = properties.datasets.retention.toKotlinDuration(),
            approvePublications = properties.approval.publications,
            approveSources = properties.approval.sources,
        )

    @Bean
    @OptIn(ExperimentalWowApi::class)
    @ConditionalOnProperty(prefix = DATASETS, name = ["serve"], matchIfMissing = true)
    fun datasetPublicationWatcher(
        queries: ObjectProvider<QueryApi>,
        datasets: QueryDatasets,
        properties: AnalyticsCatalogProperties,
        meters: ObjectProvider<MeterRegistry>,
    ): DatasetPublicationWatcher {
        val watcher = DatasetPublicationWatcher(
            { queries.getObject() },
            datasets,
            properties.datasets.refreshInterval,
            properties.datasets.reconcileInterval,
        )
        meters.ifAvailable?.let { registry ->
            Gauge.builder("$PREFIX.datasets.sync.age", watcher) { watcher.syncAge() }
                .description("Seconds since the node last read the datasets' publications; NaN before the first read.")
                .baseUnit("seconds")
                .register(registry)
            Gauge.builder("$PREFIX.datasets.publication.lag", watcher) { it.lag.toMillis() / MILLIS }
                .description("Seconds the newest publication the node applied took to be served.")
                .baseUnit("seconds")
                .register(registry)
        }
        return watcher
    }

    /** `datasetPublications`: out of service until the node read the datasets' publications once. */
    @Bean
    @ConditionalOnProperty(prefix = DATASETS, name = ["serve"], matchIfMissing = true)
    fun datasetPublicationsHealthIndicator(watcher: DatasetPublicationWatcher): HealthIndicator = HealthIndicator {
        if (watcher.synced) {
            Health.up().withDetail("lastSuccess", Instant.ofEpochMilli(watcher.lastSuccess).toString()).build()
        } else {
            Health.outOfService().withDetail("reason", "the datasets' publications were never read").build()
        }
    }

    @Bean
    @ConditionalOnProperty(prefix = AnalyticsCatalogProperties.PREFIX, name = ["watch.enabled"], matchIfMissing = true)
    fun catalogWatcher(
        catalog: WarehouseCatalog,
        commands: ObjectProvider<CommandClient>,
        properties: AnalyticsCatalogProperties,
    ): CatalogWatcher = CatalogWatcher(catalog, { commands.getObject() }, properties.refreshInterval)

    /** The settings under `wow.clickhouse`, keys relative to it, as the Sink and the dataset backend read them. */
    private fun clickHouseSettings(environment: Environment): Map<String, String> =
        Binder.get(environment).bind(ClickHouseClients.PREFIX, Bindable.mapOf(String::class.java, String::class.java))
            .orElse(emptyMap())
            .orEmpty()

    companion object {
        /**
         * Requires a signed-in caller: in development, a request without a user is no caller here
         * ([DevelopmentCallerResolver] gives the anonymous one), so the edge answers it 401 on every route, as CoSec's
         * resolver has it answered, and the console asks who calls. The health probes and the console's assets are
         * not the edge's routes, and answer without a caller.
         */
        @Bean
        @JvmStatic
        fun requireSignedInCaller(): BeanPostProcessor = object : BeanPostProcessor {
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                if (bean is DevelopmentCallerResolver) SignedInCallerResolver(bean) else bean
        }

        const val PREFIX: String = AnalyticsCatalogProperties.PREFIX
        const val DATASETS: String = "${AnalyticsCatalogProperties.PREFIX}.datasets"
        private const val MILLIS = 1000.0

        private val logger = KotlinLogging.logger {}
    }
}
