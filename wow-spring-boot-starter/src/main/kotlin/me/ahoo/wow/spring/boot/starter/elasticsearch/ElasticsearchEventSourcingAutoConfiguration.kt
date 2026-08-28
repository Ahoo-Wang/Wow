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

package me.ahoo.wow.spring.boot.starter.elasticsearch

import co.elastic.clients.json.JsonpMapper
import co.elastic.clients.json.jackson.Jackson3JsonpMapper
import co.elastic.clients.transport.rest5_client.Rest5ClientOptions
import co.elastic.clients.transport.rest5_client.SafeResponseConsumer
import me.ahoo.wow.elasticsearch.IndexTemplateInitializer
import me.ahoo.wow.elasticsearch.WowJsonpMapper
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchEventStore
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchSnapshotStore
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMappingResolver
import me.ahoo.wow.elasticsearch.query.event.ElasticsearchEventStreamQueryServiceFactory
import me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchSnapshotQueryServiceFactory
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.metrics.WowMetrics
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.ConditionalOnEventStoreStorage
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.ConditionalOnSnapshotStoreStorage
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.EventStoreBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.EventStreamQueryServiceFactoryBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.SnapshotQueryServiceFactoryBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.SnapshotStoreBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.ConditionalOnSnapshotEnabled
import me.ahoo.wow.spring.boot.starter.query.QueryProperties
import me.ahoo.wow.spring.boot.starter.query.QuerySchemaAutoConfiguration
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchClientAutoConfiguration
import org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchRestClientAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import org.springframework.data.elasticsearch.core.ReactiveElasticsearchOperations

@AutoConfiguration(
    after = [ElasticsearchRestClientAutoConfiguration::class, QuerySchemaAutoConfiguration::class],
    before = [ElasticsearchClientAutoConfiguration::class],
)
@ConditionalOnWowEnabled
@ConditionalOnElasticsearchEnabled
@ConditionalOnElasticsearchStorage
@ConditionalOnClass(ElasticsearchEventStore::class)
@EnableConfigurationProperties(
    ElasticsearchProperties::class,
    ElasticsearchQueryProperties::class,
    ElasticsearchEventStoreBatchProperties::class,
    ElasticsearchSnapshotStoreBatchProperties::class,
)
class ElasticsearchEventSourcingAutoConfiguration @Autowired constructor(
    private val elasticsearchProperties: ElasticsearchProperties,
    private val eventStoreBatchProperties: ElasticsearchEventStoreBatchProperties,
    private val snapshotStoreBatchProperties: ElasticsearchSnapshotStoreBatchProperties,
    private val queryProperties: ElasticsearchQueryProperties = ElasticsearchQueryProperties(),
) {
    @Bean
    @ConditionalOnProperty(ElasticsearchProperties.COMPATIBILITY_VERSION_KEY)
    @ConditionalOnMissingBean(Rest5ClientOptions::class)
    fun rest5ClientOptions(): Rest5ClientOptions {
        val compatibilityVersion = requireNotNull(elasticsearchProperties.compatibilityVersion) {
            "${ElasticsearchProperties.COMPATIBILITY_VERSION_KEY} must be configured when the compatibility option is enabled"
        }
        val mediaType = "application/vnd.elasticsearch+json; compatible-with=$compatibilityVersion"
        val builder = Rest5ClientOptions.Builder(SafeResponseConsumer.DEFAULT_REQUEST_OPTIONS.toBuilder())
        builder.setHeader("Accept", mediaType)
        builder.setHeader("Content-Type", mediaType)
        return builder.build()
    }

    @Bean
    @ConditionalOnMissingBean(JsonpMapper::class)
    fun jackson3JsonpMapper(): Jackson3JsonpMapper {
        return WowJsonpMapper
    }

    @Bean
    @ConditionalOnEventStoreStorage(StorageType.ELASTICSEARCH)
    fun elasticsearchEventStore(
        elasticsearchClient: ReactiveElasticsearchClient,
        indexTemplateInitializer: IndexTemplateInitializer,
        metrics: ObjectProvider<WowMetrics>,
    ): ElasticsearchEventStore {
        if (elasticsearchProperties.autoInitTemplate) {
            indexTemplateInitializer.ensureEventStreamTemplate().block()
        }
        return ElasticsearchEventStore(
            elasticsearchClient = elasticsearchClient,
            batchOptions = eventStoreBatchProperties.toOptions(),
            metrics = metrics.getIfAvailable { WowMetrics.NONE },
        )
    }

    @Bean
    @ConditionalOnEventStoreStorage(StorageType.ELASTICSEARCH)
    fun elasticsearchEventStoreBinding(
        @Qualifier("elasticsearchEventStore")
        eventStore: EventStore
    ): EventStoreBinding {
        return EventStoreBinding.storage(StorageType.ELASTICSEARCH, eventStore)
    }

    @Bean
    fun indexTemplateInitializer(elasticsearchOperations: ReactiveElasticsearchOperations): IndexTemplateInitializer {
        return IndexTemplateInitializer(elasticsearchOperations)
    }

    @Bean
    @ConditionalOnEventStoreStorage(StorageType.ELASTICSEARCH)
    fun elasticsearchEventStreamQueryServiceFactory(
        elasticsearchClient: ReactiveElasticsearchClient,
        elasticsearchIndexMappingResolver: ElasticsearchIndexMappingResolver =
            ElasticsearchIndexMappingResolver(elasticsearchClient),
        sources: List<QuerySchemaSource> = emptyList(),
        schemaQueryProperties: QueryProperties = QueryProperties(),
    ): ElasticsearchEventStreamQueryServiceFactory {
        return ElasticsearchEventStreamQueryServiceFactory(
            elasticsearchClient,
            queryProperties.batchSize,
            queryProperties.keepAlive,
            elasticsearchIndexMappingResolver,
            sources,
            schemaQueryProperties.schema.validationMode,
        )
    }

    @Bean
    @ConditionalOnEventStoreStorage(StorageType.ELASTICSEARCH)
    fun elasticsearchEventStreamQueryServiceFactoryBinding(
        elasticsearchEventStreamQueryServiceFactory: ElasticsearchEventStreamQueryServiceFactory
    ): EventStreamQueryServiceFactoryBinding {
        return EventStreamQueryServiceFactoryBinding.storage(
            StorageType.ELASTICSEARCH,
            elasticsearchEventStreamQueryServiceFactory,
        )
    }

    @Bean(name = ["elasticsearchSnapshotStore", "elasticsearchSnapshotRepository"])
    @ConditionalOnSnapshotEnabled
    @ConditionalOnSnapshotStoreStorage(StorageType.ELASTICSEARCH)
    fun elasticsearchSnapshotStore(
        elasticsearchClient: ReactiveElasticsearchClient,
        indexTemplateInitializer: IndexTemplateInitializer,
        metrics: ObjectProvider<WowMetrics>,
    ): ElasticsearchSnapshotStore {
        if (elasticsearchProperties.autoInitTemplate) {
            indexTemplateInitializer.ensureSnapshotTemplate().block()
        }
        return ElasticsearchSnapshotStore(
            elasticsearchClient = elasticsearchClient,
            batchOptions = snapshotStoreBatchProperties.toOptions(),
            metrics = metrics.getIfAvailable { WowMetrics.NONE },
        )
    }

    @Bean
    @ConditionalOnSnapshotEnabled
    @ConditionalOnSnapshotStoreStorage(StorageType.ELASTICSEARCH)
    fun elasticsearchSnapshotStoreBinding(
        @Qualifier("elasticsearchSnapshotStore")
        snapshotStore: SnapshotStore
    ): SnapshotStoreBinding {
        return SnapshotStoreBinding.storage(StorageType.ELASTICSEARCH, snapshotStore)
    }

    @Bean
    @ConditionalOnSnapshotEnabled
    @ConditionalOnSnapshotStoreStorage(StorageType.ELASTICSEARCH)
    fun elasticsearchSnapshotQueryServiceFactory(
        elasticsearchClient: ReactiveElasticsearchClient,
        elasticsearchIndexMappingResolver: ElasticsearchIndexMappingResolver,
        sources: List<QuerySchemaSource>,
        schemaQueryProperties: QueryProperties,
    ): ElasticsearchSnapshotQueryServiceFactory {
        return ElasticsearchSnapshotQueryServiceFactory(
            elasticsearchClient = elasticsearchClient,
            queryBatchSize = queryProperties.batchSize,
            queryKeepAlive = queryProperties.keepAlive,
            indexMappingResolver = elasticsearchIndexMappingResolver,
            schemaSources = sources,
            validationMode = schemaQueryProperties.schema.validationMode,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun elasticsearchIndexMappingResolver(
        elasticsearchClient: ReactiveElasticsearchClient,
    ): ElasticsearchIndexMappingResolver = ElasticsearchIndexMappingResolver(elasticsearchClient)

    @Bean
    @ConditionalOnSnapshotEnabled
    @ConditionalOnSnapshotStoreStorage(StorageType.ELASTICSEARCH)
    fun elasticsearchSnapshotQueryServiceFactoryBinding(
        elasticsearchSnapshotQueryServiceFactory: ElasticsearchSnapshotQueryServiceFactory
    ): SnapshotQueryServiceFactoryBinding {
        return SnapshotQueryServiceFactoryBinding.storage(
            StorageType.ELASTICSEARCH,
            elasticsearchSnapshotQueryServiceFactory,
        )
    }
}
