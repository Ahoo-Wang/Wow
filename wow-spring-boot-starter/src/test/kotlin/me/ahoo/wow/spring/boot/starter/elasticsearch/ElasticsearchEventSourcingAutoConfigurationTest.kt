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
import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.elasticsearch.IndexTemplateInitializer
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchEventStore
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchSnapshotStore
import me.ahoo.wow.elasticsearch.query.event.ElasticsearchEventStreamQueryServiceFactory
import me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchSnapshotQueryServiceFactory
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.EventStoreBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.SnapshotStoreBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreProperties
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import org.springframework.data.elasticsearch.core.ReactiveElasticsearchOperations
import org.springframework.data.elasticsearch.core.ReactiveIndexOperations
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates
import reactor.kotlin.core.publisher.toMono

internal class ElasticsearchEventSourcingAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun `should load context with elasticsearch event sourcing beans`() {
        val elasticsearchTemplate = mockk<ReactiveElasticsearchOperations> {
            every { indexOps(any<IndexCoordinates>()) } returns mockk<ReactiveIndexOperations> {
                every { putIndexTemplate(any()) } returns true.toMono()
            }
        }
        contextRunner
            .enableWow()
            .withPropertyValues("${SnapshotProperties.STORAGE}=${StorageType.ELASTICSEARCH_NAME}")
            .withPropertyValues("${EventStoreProperties.STORAGE}=${StorageType.ELASTICSEARCH_NAME}")
            .withBean(ReactiveElasticsearchClient::class.java, {
                mock(ReactiveElasticsearchClient::class.java)
            })
            .withBean(ReactiveElasticsearchOperations::class.java, {
                elasticsearchTemplate
            })
            .withUserConfiguration(
                ElasticsearchEventSourcingAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(Jackson3JsonpMapper::class.java)
                    .hasSingleBean(ElasticsearchEventStore::class.java)
                    .hasSingleBean(ElasticsearchEventStreamQueryServiceFactory::class.java)
                    .hasSingleBean(IndexTemplateInitializer::class.java)
                    .hasBean("elasticsearchSnapshotStore")
                    .hasBean("elasticsearchSnapshotRepository")
                    .hasSingleBean(ElasticsearchSnapshotStore::class.java)
                    .hasSingleBean(EventStoreBinding::class.java)
                    .hasSingleBean(SnapshotStoreBinding::class.java)
                    .hasSingleBean(ElasticsearchSnapshotQueryServiceFactory::class.java)
                context.containsBean("snapshotRepository").assert().isFalse()
                val eventStore = context.getBean(ElasticsearchEventStore::class.java)
                val eventBinding = context.getBean(EventStoreBinding::class.java)
                eventBinding.storage.assert().isEqualTo(StorageType.ELASTICSEARCH)
                eventBinding.eventStore.assert().isSameAs(eventStore)

                val snapshotStore = context.getBean(ElasticsearchSnapshotStore::class.java)
                val snapshotBinding = context.getBean(SnapshotStoreBinding::class.java)
                snapshotBinding.storage.assert().isEqualTo(StorageType.ELASTICSEARCH)
                snapshotBinding.snapshotStore.assert().isSameAs(snapshotStore)
            }
    }
}
