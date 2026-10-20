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

package me.ahoo.wow.datasetregistry.starter

import me.ahoo.wow.clickhouse.query.ClickHouseExecutor
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.datasetregistry.DatasetRegistryService
import me.ahoo.wow.datasetregistry.api.DatasetRegistryErrorCodes
import me.ahoo.wow.datasetregistry.domain.DatasetPublicationCheck
import me.ahoo.wow.datasetregistry.domain.DatasetRegistryException
import me.ahoo.wow.datasetregistry.domain.DatasetState
import me.ahoo.wow.datasetregistry.domain.DatasetTableInspector
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.query.dataset.DatasetQueryBackendFactory
import me.ahoo.wow.query.schema.QueryDatasets
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.clickhouse.ClickHouseDatasetAutoConfiguration
import me.ahoo.wow.spring.boot.starter.clickhouse.ClickHouseDatasetProperties
import me.ahoo.wow.spring.boot.starter.clickhouse.ClickHouseDatasetSources
import me.ahoo.wow.spring.boot.starter.query.QueryAutoConfiguration
import me.ahoo.wow.spring.boot.starter.query.QuerySchemaAutoConfiguration
import me.ahoo.wow.spring.query.snapshotQueryGatewayBeanName
import me.ahoo.wow.webflux.exception.ErrorHttpStatusMapping
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import reactor.core.publisher.Mono

/**
 * Embeds the dataset registry (dataset platform design §4) in a Wow service: the `Dataset` aggregate's ports, and the
 * projection that serves its published datasets on this instance beside those of `wow.query.datasets`.
 *
 * The registry's command routes are the bounded context `dataset-registry`'s; every one of them changes what the
 * platform serves, so only platform administrators should reach them (a CoSec rule on the path prefix `/dataset-registry/`).
 */
@AutoConfiguration(
    after = [QuerySchemaAutoConfiguration::class, QueryAutoConfiguration::class, ClickHouseDatasetAutoConfiguration::class],
)
@ConditionalOnWowEnabled
@ConditionalOnProperty(value = [DatasetRegistryProperties.ENABLED_KEY], matchIfMissing = true, havingValue = "true")
@ConditionalOnBean(QueryDatasets::class)
@EnableConfigurationProperties(DatasetRegistryProperties::class)
class DatasetRegistryAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun publishedDatasetSource(beanFactory: BeanFactory): PublishedDatasetSource = SnapshotPublishedDatasetSource {
        @Suppress("UNCHECKED_CAST")
        beanFactory.getBean(DATASET.snapshotQueryGatewayBeanName()) as SnapshotQueryGateway<DatasetState>
    }

    @Bean
    @ConditionalOnMissingBean
    fun datasetRegistryProjection(
        datasets: QueryDatasets,
        source: PublishedDatasetSource,
        properties: DatasetRegistryProperties,
    ): DatasetRegistryProjection = DatasetRegistryProjection(datasets, source, properties.refreshInterval)

    @Bean
    @ConditionalOnMissingBean
    fun datasetPublicationCheck(
        datasets: QueryDatasets,
        projection: DatasetRegistryProjection,
        factory: ObjectProvider<DatasetQueryBackendFactory>,
    ): DatasetPublicationCheck = CompilingDatasetPublicationCheck(
        datasets = datasets,
        registered = projection::names,
        aggregates = MetadataSearcher.namedAggregateType.keys.mapTo(HashSet()) { it.materialize() },
        factory = factory.ifAvailable,
    )

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ClickHouseExecutor::class)
    @ConditionalOnBean(ClickHouseExecutor::class)
    class ClickHouseConfiguration {
        @Bean
        @ConditionalOnMissingBean
        fun clickHouseDatasetTableInspector(
            executor: ClickHouseExecutor,
            properties: ClickHouseDatasetProperties,
            sources: ObjectProvider<ClickHouseDatasetSources>,
        ): DatasetTableInspector = ClickHouseDatasetTableInspector(
            executor,
            properties.database,
            sources.ifAvailable?.sources.orEmpty(),
        )
    }

    /** Without a backend that can read a table, drafting is refused. */
    @Bean
    @ConditionalOnMissingBean
    fun datasetTableInspector(): DatasetTableInspector = DatasetTableInspector { _, _ ->
        Mono.error(DatasetRegistryException.invalid("No dataset backend here can read a table to draft from."))
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ErrorHttpStatusMapping::class)
    class ErrorStatusConfiguration {
        @Bean
        fun datasetRegistryErrorStatuses(): DatasetRegistryErrorStatuses = DatasetRegistryErrorStatuses.also {
            it.register()
        }
    }

    companion object {
        val DATASET: MaterializedNamedAggregate = MaterializedNamedAggregate(
            DatasetRegistryService.SERVICE_NAME,
            DatasetRegistryService.DATASET_AGGREGATE_NAME,
        )
    }
}

/** The HTTP status of each of the dataset registry's error codes. */
object DatasetRegistryErrorStatuses {
    val STATUSES: Map<String, HttpStatus> = mapOf(
        DatasetRegistryErrorCodes.DATASET_INVALID to HttpStatus.BAD_REQUEST,
        DatasetRegistryErrorCodes.DATASET_NOT_PUBLISHABLE to HttpStatus.CONFLICT,
        DatasetRegistryErrorCodes.DATASET_NAME_TAKEN to HttpStatus.CONFLICT,
    )

    fun register() {
        STATUSES.forEach { (errorCode, status) -> ErrorHttpStatusMapping.register(errorCode, status) }
    }
}
