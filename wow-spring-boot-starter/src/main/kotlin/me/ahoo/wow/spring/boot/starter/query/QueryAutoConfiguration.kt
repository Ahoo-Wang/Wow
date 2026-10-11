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
package me.ahoo.wow.spring.boot.starter.query

import io.micrometer.core.instrument.MeterRegistry
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.query.CompositeQueryObserver
import me.ahoo.wow.query.QueryBackendStorages
import me.ahoo.wow.query.QueryEntryPolicy
import me.ahoo.wow.query.QueryLogObserver
import me.ahoo.wow.query.QueryMetricsObserver
import me.ahoo.wow.query.QueryObserver
import me.ahoo.wow.query.QueryPolicy
import me.ahoo.wow.query.dataset.DatasetQueryBackendFactory
import me.ahoo.wow.query.dataset.DatasetQueryGateways
import me.ahoo.wow.query.dataset.DefaultDatasetQueryGateway
import me.ahoo.wow.query.event.EventStreamQueryBackendFactory
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.schema.QueryDatasets
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.schema.QuerySensitivityPolicy
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactory
import me.ahoo.wow.query.snapshot.filter.AbacQueryOptions
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.metrics.isMetricsEnabled
import me.ahoo.wow.spring.query.EventStreamQueryGatewayRegistrar
import me.ahoo.wow.spring.query.EventStreamQueryGatewayRegistrar.Companion.EVENT_STREAM_QUERY_OBSERVER_BEAN_NAME
import me.ahoo.wow.spring.query.SnapshotQueryGatewayRegistrar
import me.ahoo.wow.spring.query.SnapshotQueryGatewayRegistrar.Companion.SNAPSHOT_QUERY_OBSERVER_BEAN_NAME
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment

/**
 * Query AutoConfiguration .
 *
 * @author ahoo wang
 */
@AutoConfiguration(after = [QuerySchemaAutoConfiguration::class])
@Import(SnapshotQueryGatewayRegistrar::class, EventStreamQueryGatewayRegistrar::class)
@ConditionalOnWowEnabled
@EnableConfigurationProperties(QueryProperties::class)
class QueryAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun queryEntryPolicy(queryProperties: QueryProperties, environment: Environment): QueryEntryPolicy =
        QueryEntryPolicy(
            requireExplicitEntry = queryProperties.requireExplicitEntry,
            requireAuthenticatedScope = queryProperties.requireAuthenticatedScope,
            http = queryProperties.http.withLegacyKeys(environment).toBudget(),
        )

    /** The options an application's `AbacQueryPolicy` takes in its constructor. */
    @Bean
    @ConditionalOnMissingBean
    fun abacQueryOptions(queryProperties: QueryProperties): AbacQueryOptions = queryProperties.abac.toOptions()

    @Bean(SNAPSHOT_QUERY_OBSERVER_BEAN_NAME)
    @ConditionalOnMissingBean(name = [SNAPSHOT_QUERY_OBSERVER_BEAN_NAME])
    fun snapshotQueryObserver(meterRegistry: ObjectProvider<MeterRegistry>, environment: Environment): QueryObserver =
        queryObserver(meterRegistry, environment)

    @Bean(EVENT_STREAM_QUERY_OBSERVER_BEAN_NAME)
    @ConditionalOnMissingBean(name = [EVENT_STREAM_QUERY_OBSERVER_BEAN_NAME])
    fun eventStreamQueryObserver(
        meterRegistry: ObjectProvider<MeterRegistry>,
        environment: Environment
    ): QueryObserver =
        queryObserver(meterRegistry, environment)

    /** Logs failures and, with `wow.metrics.enabled` and a registry, publishes `wow.query` meters. */
    private fun queryObserver(meterRegistry: ObjectProvider<MeterRegistry>, environment: Environment): QueryObserver {
        val registry = meterRegistry.getIfAvailable()?.takeIf { environment.isMetricsEnabled() }
            ?: return QueryLogObserver()
        return CompositeQueryObserver(listOf(QueryLogObserver(), QueryMetricsObserver(registry)))
    }

    /**
     * The Catalog: compiles every aggregate model's schema from the query schema sources, the sensitivity policy and
     * the native facts its storage adapter reports, or with the application's [QueryModelCompiler] when it registers
     * one. Gateways, point reads and the descriptor route read it.
     */
    @Bean
    @ConditionalOnMissingBean
    fun querySchemaCatalog(
        snapshotQueryBackendFactories: ObjectProvider<SnapshotQueryBackendFactory>,
        eventStreamQueryBackendFactories: ObjectProvider<EventStreamQueryBackendFactory>,
        compiler: ObjectProvider<QueryModelCompiler>,
        sources: ObjectProvider<QuerySchemaSource>,
        sensitivity: ObjectProvider<QuerySensitivityPolicy>,
        meterRegistry: ObjectProvider<MeterRegistry>,
        environment: Environment,
        queryProperties: QueryProperties,
        queryDatasets: ObjectProvider<QueryDatasets>,
        datasetQueryBackendFactory: ObjectProvider<DatasetQueryBackendFactory>,
    ): QuerySchemaCatalog {
        val datasets = queryDatasets.getIfAvailable { QueryDatasets.NONE }
        return QuerySchemaCatalog(
            storages = QueryBackendStorages(
                snapshots = snapshotQueryBackendFactories.getIfAvailable { UnavailableSnapshotQueryBackendFactory },
                eventStreams = eventStreamQueryBackendFactories.getIfAvailable { UnavailableEventStreamQueryBackendFactory },
                datasetFactory = datasetQueryBackendFactory.getIfAvailable(),
                datasets = datasets,
            ),
            compiler = compiler.getIfAvailable {
                QueryModelCompiler.of(
                    sources = sources.orderedStream().toList(),
                    sensitivity = sensitivity.getIfAvailable { QuerySensitivityPolicy.DEFAULT },
                    legacyDeclarations = queryProperties.schema.legacyDeclarations,
                    datasets = datasets,
                )
            },
            aggregates = MetadataSearcher.namedAggregateType.keys,
            meterRegistry = meterRegistry.getIfAvailable()?.takeIf { environment.isMetricsEnabled() },
            datasets = datasets,
        )
    }

    @Bean(DATASET_QUERY_OBSERVER_BEAN_NAME)
    @ConditionalOnMissingBean(name = [DATASET_QUERY_OBSERVER_BEAN_NAME])
    fun datasetQueryObserver(meterRegistry: ObjectProvider<MeterRegistry>, environment: Environment): QueryObserver =
        queryObserver(meterRegistry, environment)

    /**
     * One gateway per served dataset (`wow.query.datasets`, and any that joins later), admitted like an aggregate's:
     * the same filters, policies and entry policy, the Catalog's schema and the dataset backend's storage. Built after
     * the Catalog, so a dataset that changes is recompiled before its new gateway reads its schema.
     */
    @Bean
    @ConditionalOnMissingBean
    @Suppress("LongParameterList")
    fun datasetQueryGateways(
        queryDatasets: ObjectProvider<QueryDatasets>,
        datasetQueryBackendFactory: ObjectProvider<DatasetQueryBackendFactory>,
        catalog: QuerySchemaCatalog,
        filters: ObjectProvider<QueryFilter>,
        policies: ObjectProvider<QueryPolicy>,
        @Qualifier(DATASET_QUERY_OBSERVER_BEAN_NAME) observer: QueryObserver,
        entryPolicy: QueryEntryPolicy,
    ): DatasetQueryGateways {
        val datasets = queryDatasets.getIfAvailable { QueryDatasets.NONE }
        val factory = datasetQueryBackendFactory.getIfAvailable()
        val queryFilters = filters.orderedStream().toList()
        val queryPolicies = policies.orderedStream().toList()
        return DatasetQueryGateways.of(
            datasets,
            requireServable = { dataset ->
                requireNotNull(factory) {
                    "Dataset [${dataset.namedAggregate}] has no dataset query backend to serve it " +
                        "(enable one, such as wow.query.clickhouse)."
                }
            },
        ) { dataset ->
            DefaultDatasetQueryGateway(
                dataset = dataset,
                backend = checkNotNull(factory).create(dataset).backend,
                schemaProvider = catalog.provider(dataset.namedAggregate, QueryModel.DATASET),
                filters = queryFilters,
                policies = queryPolicies,
                observer = observer,
                entryPolicy = entryPolicy,
            )
        }
    }

    @Bean
    @ConditionalOnMissingBean(SnapshotQueryBackendFactory::class)
    fun unavailableSnapshotQueryBackendFactory(): SnapshotQueryBackendFactory =
        UnavailableSnapshotQueryBackendFactory

    @Bean
    @ConditionalOnMissingBean(EventStreamQueryBackendFactory::class)
    fun unavailableEventStreamQueryBackendFactory(): EventStreamQueryBackendFactory =
        UnavailableEventStreamQueryBackendFactory
}

/** The bean name of the observer the dataset gateways share; register a bean of this name to replace it. */
const val DATASET_QUERY_OBSERVER_BEAN_NAME = "datasetQueryObserver"
