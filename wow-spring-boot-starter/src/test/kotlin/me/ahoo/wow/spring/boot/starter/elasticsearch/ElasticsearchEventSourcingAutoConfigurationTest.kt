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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.elasticsearch.IndexTemplateInitializer
import me.ahoo.wow.elasticsearch.WowJsonpMapper
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchEventStore
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchSnapshotStore
import me.ahoo.wow.elasticsearch.query.event.ElasticsearchEventStreamQueryServiceFactory
import me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchSnapshotQueryServiceFactory
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.EventStoreBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.SnapshotStoreBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.StorageRoutingProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreProperties
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.data.elasticsearch.autoconfigure.DataElasticsearchAutoConfiguration
import org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchClientAutoConfiguration
import org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchRestClientAutoConfiguration
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import org.springframework.data.elasticsearch.core.ReactiveElasticsearchOperations
import org.springframework.data.elasticsearch.core.ReactiveIndexOperations
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

internal class ElasticsearchEventSourcingAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun `should auto configure reactive elasticsearch infrastructure from feature dependencies`() {
        ApplicationContextRunner()
            .enableWow()
            .withConfiguration(
                AutoConfigurations.of(
                    ElasticsearchRestClientAutoConfiguration::class.java,
                    ElasticsearchClientAutoConfiguration::class.java,
                    DataElasticsearchAutoConfiguration::class.java,
                    ElasticsearchEventSourcingAutoConfiguration::class.java,
                ),
            )
            .withPropertyValues(
                "${EventStoreProperties.STORAGE}=${StorageType.ELASTICSEARCH_NAME}",
                "${SnapshotProperties.STORAGE}=${StorageType.MONGO_NAME}",
                "${ElasticsearchProperties.PREFIX}.auto-init-template=false",
            )
            .run { context ->
                context.assert()
                    .hasNotFailed()
                    .hasSingleBean(ReactiveElasticsearchClient::class.java)
                    .hasSingleBean(ReactiveElasticsearchOperations::class.java)
                    .hasSingleBean(IndexTemplateInitializer::class.java)
                    .hasSingleBean(ElasticsearchEventStore::class.java)
                    .hasSingleBean(JsonpMapper::class.java)
                context.getBean(JsonpMapper::class.java).assert().isSameAs(WowJsonpMapper)
            }
    }

    @Test
    fun `should not load elasticsearch beans when no storage route uses elasticsearch`() {
        val indexOperations = successfulIndexOperations()
        elasticsearchContextRunner(indexOperations)
            .withPropertyValues(
                "${EventStoreProperties.STORAGE}=${StorageType.MONGO_NAME}",
                "${SnapshotProperties.STORAGE}=${StorageType.MONGO_NAME}",
            )
            .run { context ->
                context.assert()
                    .hasNotFailed()
                    .doesNotHaveBean(JsonpMapper::class.java)
                    .doesNotHaveBean(IndexTemplateInitializer::class.java)
                    .doesNotHaveBean(ElasticsearchEventStore::class.java)
                    .doesNotHaveBean(ElasticsearchSnapshotStore::class.java)
                verify(exactly = 0) { indexOperations.putIndexTemplate(any()) }
            }
    }

    @Test
    fun `should not load context when elasticsearch is disabled`() {
        contextRunner
            .enableWow()
            .withPropertyValues("${ElasticsearchProperties.PREFIX}.enabled=false")
            .withPropertyValues("${SnapshotProperties.STORAGE}=${StorageType.ELASTICSEARCH_NAME}")
            .withPropertyValues("${EventStoreProperties.STORAGE}=${StorageType.ELASTICSEARCH_NAME}")
            .withUserConfiguration(ElasticsearchEventSourcingAutoConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasNotFailed()
                    .doesNotHaveBean(Jackson3JsonpMapper::class.java)
                    .doesNotHaveBean(ElasticsearchEventStore::class.java)
                    .doesNotHaveBean(ElasticsearchSnapshotStore::class.java)
                    .doesNotHaveBean(IndexTemplateInitializer::class.java)
            }
    }

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

    @Test
    fun `should initialize only event stream template for elasticsearch event route`() {
        val indexOperations = successfulIndexOperations()
        elasticsearchContextRunner(indexOperations)
            .withPropertyValues(
                "${EventStoreProperties.STORAGE}=${StorageType.MONGO_NAME}",
                "${SnapshotProperties.STORAGE}=${StorageType.MONGO_NAME}",
                "${StorageRoutingProperties.AGGREGATES}.order.event.storage=${StorageType.ELASTICSEARCH_NAME}",
            )
            .run { context ->
                context.assert()
                    .hasNotFailed()
                    .hasSingleBean(IndexTemplateInitializer::class.java)
                    .hasSingleBean(ElasticsearchEventStore::class.java)
                    .doesNotHaveBean(ElasticsearchSnapshotStore::class.java)
                verify(exactly = 1) {
                    indexOperations.putIndexTemplate(match { it.name == "wow-event-stream-template" })
                }
                verify(exactly = 0) {
                    indexOperations.putIndexTemplate(match { it.name == "wow-snapshot-template" })
                }
            }
    }

    @Test
    fun `should initialize only snapshot template for elasticsearch snapshot route`() {
        val indexOperations = successfulIndexOperations()
        elasticsearchContextRunner(indexOperations)
            .withPropertyValues(
                "${EventStoreProperties.STORAGE}=${StorageType.MONGO_NAME}",
                "${SnapshotProperties.STORAGE}=${StorageType.MONGO_NAME}",
                "${StorageRoutingProperties.AGGREGATES}.cart.snapshot.storage=${StorageType.ELASTICSEARCH_NAME}",
            )
            .run { context ->
                context.assert()
                    .hasNotFailed()
                    .hasSingleBean(IndexTemplateInitializer::class.java)
                    .doesNotHaveBean(ElasticsearchEventStore::class.java)
                    .hasSingleBean(ElasticsearchSnapshotStore::class.java)
                verify(exactly = 0) {
                    indexOperations.putIndexTemplate(match { it.name == "wow-event-stream-template" })
                }
                verify(exactly = 1) {
                    indexOperations.putIndexTemplate(match { it.name == "wow-snapshot-template" })
                }
            }
    }

    @Test
    fun `should not initialize templates when auto initialization is disabled`() {
        val indexOperations = successfulIndexOperations()
        elasticsearchContextRunner(indexOperations)
            .withPropertyValues(
                "${EventStoreProperties.STORAGE}=${StorageType.ELASTICSEARCH_NAME}",
                "${SnapshotProperties.STORAGE}=${StorageType.ELASTICSEARCH_NAME}",
                "${ElasticsearchProperties.PREFIX}.auto-init-template=false",
            )
            .run { context ->
                context.assert().hasNotFailed()
                verify(exactly = 0) { indexOperations.putIndexTemplate(any()) }
            }
    }

    @Test
    fun `should back off when a custom jsonp mapper is provided`() {
        val customMapper = mockk<JsonpMapper>()
        val indexOperations = successfulIndexOperations()
        elasticsearchContextRunner(indexOperations)
            .withPropertyValues(
                "${EventStoreProperties.STORAGE}=${StorageType.ELASTICSEARCH_NAME}",
                "${SnapshotProperties.STORAGE}=${StorageType.MONGO_NAME}",
                "${ElasticsearchProperties.PREFIX}.auto-init-template=false",
            )
            .withBean(JsonpMapper::class.java, { customMapper })
            .run { context ->
                context.assert()
                    .hasNotFailed()
                    .hasSingleBean(JsonpMapper::class.java)
                    .doesNotHaveBean(Jackson3JsonpMapper::class.java)
                context.getBean(JsonpMapper::class.java).assert().isSameAs(customMapper)
            }
    }

    @Test
    fun `should fail startup when template initialization fails`() {
        val failure = IllegalStateException("template initialization failed")
        val indexOperations = mockk<ReactiveIndexOperations> {
            every { putIndexTemplate(any()) } returns Mono.error(failure)
        }

        elasticsearchContextRunner(indexOperations)
            .withPropertyValues(
                "${EventStoreProperties.STORAGE}=${StorageType.ELASTICSEARCH_NAME}",
                "${SnapshotProperties.STORAGE}=${StorageType.MONGO_NAME}",
            )
            .run { context ->
                context.startupFailure.assert().isNotNull()
            }
    }

    @Test
    fun `should fail startup when template initialization is not acknowledged`() {
        val indexOperations = mockk<ReactiveIndexOperations> {
            every { putIndexTemplate(any()) } returns Mono.just(false)
        }

        elasticsearchContextRunner(indexOperations)
            .withPropertyValues(
                "${EventStoreProperties.STORAGE}=${StorageType.ELASTICSEARCH_NAME}",
                "${SnapshotProperties.STORAGE}=${StorageType.MONGO_NAME}",
            )
            .run { context ->
                context.startupFailure.assert().isNotNull()
            }
    }

    private fun successfulIndexOperations(): ReactiveIndexOperations = mockk {
        every { putIndexTemplate(any()) } returns true.toMono()
    }

    private fun elasticsearchContextRunner(indexOperations: ReactiveIndexOperations): ApplicationContextRunner {
        val elasticsearchOperations = mockk<ReactiveElasticsearchOperations> {
            every { indexOps(any<IndexCoordinates>()) } returns indexOperations
        }
        return contextRunner
            .enableWow()
            .withBean(ReactiveElasticsearchClient::class.java, {
                mock(ReactiveElasticsearchClient::class.java)
            })
            .withBean(ReactiveElasticsearchOperations::class.java, {
                elasticsearchOperations
            })
            .withUserConfiguration(ElasticsearchEventSourcingAutoConfiguration::class.java)
    }
}
