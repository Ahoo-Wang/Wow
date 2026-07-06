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

import co.elastic.clients.json.jackson.Jackson3JsonpMapper
import me.ahoo.wow.elasticsearch.IndexTemplateInitializer
import me.ahoo.wow.elasticsearch.WowJsonpMapper
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchEventStore
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchSnapshotStore
import me.ahoo.wow.elasticsearch.query.event.ElasticsearchEventStreamQueryServiceFactory
import me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchSnapshotQueryServiceFactory
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.ConditionalOnEventStoreStorage
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.ConditionalOnSnapshotStoreStorage
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.EventStoreBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.SnapshotStoreBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.ConditionalOnSnapshotEnabled
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreProperties
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchRestClientAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import org.springframework.data.elasticsearch.core.ReactiveElasticsearchOperations

@AutoConfiguration(after = [ElasticsearchRestClientAutoConfiguration::class])
@ConditionalOnWowEnabled
@ConditionalOnClass(ElasticsearchEventStore::class)
@EnableConfigurationProperties(ElasticsearchProperties::class)
class ElasticsearchEventSourcingAutoConfiguration(private val elasticsearchProperties: ElasticsearchProperties) {

    @Bean
    fun jackson3JsonpMapper(): Jackson3JsonpMapper {
        return WowJsonpMapper
    }

    @Bean
    @ConditionalOnEventStoreStorage(StorageType.ELASTICSEARCH)
    fun elasticsearchEventStore(
        elasticsearchClient: ReactiveElasticsearchClient
    ): ElasticsearchEventStore {
        return ElasticsearchEventStore(elasticsearchClient)
    }

    @Bean
    @ConditionalOnEventStoreStorage(StorageType.ELASTICSEARCH)
    fun elasticsearchEventStoreBinding(elasticsearchEventStore: ElasticsearchEventStore): EventStoreBinding {
        return EventStoreBinding.storage(StorageType.ELASTICSEARCH, elasticsearchEventStore)
    }

    @Bean
    @ConditionalOnEventStoreStorage(StorageType.ELASTICSEARCH)
    fun indexTemplateInitializer(elasticsearchOperations: ReactiveElasticsearchOperations): IndexTemplateInitializer {
        val initializer = IndexTemplateInitializer(elasticsearchOperations)
        if (elasticsearchProperties.autoInitTemplate) {
            initializer.initAll()
        }
        return initializer
    }

    @Bean
    @ConditionalOnProperty(
        EventStoreProperties.STORAGE,
        havingValue = StorageType.ELASTICSEARCH_NAME,
    )
    fun elasticsearchEventStreamQueryServiceFactory(
        elasticsearchClient: ReactiveElasticsearchClient
    ): EventStreamQueryServiceFactory {
        return ElasticsearchEventStreamQueryServiceFactory(elasticsearchClient)
    }

    @Bean(name = ["elasticsearchSnapshotStore", "snapshotRepository"])
    @ConditionalOnSnapshotEnabled
    @ConditionalOnSnapshotStoreStorage(StorageType.ELASTICSEARCH)
    fun elasticsearchSnapshotStore(
        elasticsearchClient: ReactiveElasticsearchClient
    ): ElasticsearchSnapshotStore {
        return ElasticsearchSnapshotStore(elasticsearchClient)
    }

    @Bean
    @ConditionalOnSnapshotEnabled
    @ConditionalOnSnapshotStoreStorage(StorageType.ELASTICSEARCH)
    fun elasticsearchSnapshotStoreBinding(
        elasticsearchSnapshotStore: ElasticsearchSnapshotStore
    ): SnapshotStoreBinding {
        return SnapshotStoreBinding.storage(StorageType.ELASTICSEARCH, elasticsearchSnapshotStore)
    }

    @Bean
    @ConditionalOnSnapshotEnabled
    @ConditionalOnProperty(
        SnapshotProperties.STORAGE,
        havingValue = StorageType.ELASTICSEARCH_NAME,
    )
    fun elasticsearchSnapshotQueryServiceFactory(
        elasticsearchClient: ReactiveElasticsearchClient,
    ): SnapshotQueryServiceFactory {
        return ElasticsearchSnapshotQueryServiceFactory(elasticsearchClient)
    }
}
