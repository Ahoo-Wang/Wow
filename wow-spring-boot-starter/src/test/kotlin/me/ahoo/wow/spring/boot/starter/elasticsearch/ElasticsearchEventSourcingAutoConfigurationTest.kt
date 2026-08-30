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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.elasticsearch.IndexTemplateInitializer
import me.ahoo.wow.elasticsearch.WowJsonpMapper
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchEventStore
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchEventStoreBatchOptions
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchSnapshotStore
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchSnapshotStoreBatchOptions
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMappingResolver
import me.ahoo.wow.elasticsearch.query.event.ElasticsearchEventStreamQueryBackendFactory
import me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchSnapshotQueryBackendFactory
import me.ahoo.wow.metrics.WowMetrics
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.EventStoreBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.SnapshotStoreBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.StorageRoutingProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreProperties
import me.ahoo.wow.spring.boot.starter.query.QueryProperties
import me.ahoo.wow.spring.boot.starter.query.QuerySchemaAutoConfiguration
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.support.StaticListableBeanFactory
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
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

internal class ElasticsearchEventSourcingAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(QuerySchemaAutoConfiguration::class.java)
    private val metricsProvider = StaticListableBeanFactory(
        mapOf("wowMetrics" to WowMetrics.NONE)
    ).getBeanProvider(WowMetrics::class.java)

    @Test
    fun `event stream query factory should use default schema collaborators`() {
        ElasticsearchEventSourcingAutoConfiguration(
            elasticsearchProperties = ElasticsearchProperties(autoInitTemplate = false),
            eventStoreBatchProperties = ElasticsearchEventStoreBatchProperties(),
            snapshotStoreBatchProperties = ElasticsearchSnapshotStoreBatchProperties(),
        ).elasticsearchEventStreamQueryBackendFactory(
            elasticsearchClient = mock(ReactiveElasticsearchClient::class.java),
            schemaQueryProperties = QueryProperties(
                schema = QueryProperties.Schema(
                    validationMode = QuerySchemaValidationMode.COMPATIBLE,
                ),
            ),
        ).assert().isInstanceOf(ElasticsearchEventStreamQueryBackendFactory::class.java)
    }

    @Test
    fun `default batch properties should be used`() {
        val autoConfiguration = ElasticsearchEventSourcingAutoConfiguration(
            elasticsearchProperties = ElasticsearchProperties(autoInitTemplate = false),
            eventStoreBatchProperties = ElasticsearchEventStoreBatchProperties(),
            snapshotStoreBatchProperties = ElasticsearchSnapshotStoreBatchProperties(),
        )
        val elasticsearchClient = mock(ReactiveElasticsearchClient::class.java)
        val indexTemplateInitializer = mockk<IndexTemplateInitializer>()

        autoConfiguration.elasticsearchEventStore(elasticsearchClient, indexTemplateInitializer, metricsProvider)
            .use { eventStore ->
                eventStore.batchOptions.assert()
                    .isEqualTo(ElasticsearchEventStoreBatchOptions())
            }
        autoConfiguration.elasticsearchSnapshotStore(elasticsearchClient, indexTemplateInitializer, metricsProvider)
            .use { snapshotStore ->
                snapshotStore.batchOptions.assert()
                    .isEqualTo(ElasticsearchSnapshotStoreBatchOptions())
            }
    }

    @Test
    fun `should pass query schema sources to snapshot factory`() {
        val expected = IllegalStateException("query schema source was used")
        val configuration = ElasticsearchEventSourcingAutoConfiguration(
            elasticsearchProperties = ElasticsearchProperties(autoInitTemplate = false),
            eventStoreBatchProperties = ElasticsearchEventStoreBatchProperties(),
            snapshotStoreBatchProperties = ElasticsearchSnapshotStoreBatchProperties(),
        )
        val factory = configuration.elasticsearchSnapshotQueryBackendFactory(
            elasticsearchClient = mock(ReactiveElasticsearchClient::class.java),
            elasticsearchIndexMappingResolver = mockk<ElasticsearchIndexMappingResolver>(),
            sources = listOf(failingQuerySchemaSource(expected)),
            schemaQueryProperties = QueryProperties(
                schema = QueryProperties.Schema(
                    validationMode = QuerySchemaValidationMode.COMPATIBLE,
                ),
            ),
        )

        (factory.create<Any>(MOCK_AGGREGATE_METADATA) as QueryModelSchemaProvider)
            .schema()
            .test()
            .expectErrorSatisfies { it.assert().isSameAs(expected) }
            .verify()
    }

    @Test
    fun `should pass query schema sources to event stream factory`() {
        val expected = IllegalStateException("query schema source was used")
        val configuration = ElasticsearchEventSourcingAutoConfiguration(
            elasticsearchProperties = ElasticsearchProperties(autoInitTemplate = false),
            eventStoreBatchProperties = ElasticsearchEventStoreBatchProperties(),
            snapshotStoreBatchProperties = ElasticsearchSnapshotStoreBatchProperties(),
        )
        val factory = configuration.elasticsearchEventStreamQueryBackendFactory(
            elasticsearchClient = mock(ReactiveElasticsearchClient::class.java),
            elasticsearchIndexMappingResolver = mockk<ElasticsearchIndexMappingResolver>(),
            sources = listOf(failingQuerySchemaSource(expected)),
            schemaQueryProperties = QueryProperties(
                schema = QueryProperties.Schema(
                    validationMode = QuerySchemaValidationMode.STRICT,
                ),
            ),
        )

        (factory.create(MOCK_AGGREGATE_METADATA) as QueryModelSchemaProvider)
            .schema()
            .test()
            .expectErrorSatisfies { it.assert().isSameAs(expected) }
            .verify()
    }

    @Test
    fun `should auto configure reactive elasticsearch infrastructure from feature dependencies`() {
        ApplicationContextRunner()
            .enableWow()
            .withConfiguration(
                AutoConfigurations.of(
                    ElasticsearchRestClientAutoConfiguration::class.java,
                    ElasticsearchClientAutoConfiguration::class.java,
                    DataElasticsearchAutoConfiguration::class.java,
                    QuerySchemaAutoConfiguration::class.java,
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
    fun `should configure Elasticsearch compatibility media type`() {
        ApplicationContextRunner()
            .enableWow()
            .withConfiguration(
                AutoConfigurations.of(
                    ElasticsearchRestClientAutoConfiguration::class.java,
                    ElasticsearchClientAutoConfiguration::class.java,
                    DataElasticsearchAutoConfiguration::class.java,
                    QuerySchemaAutoConfiguration::class.java,
                    ElasticsearchEventSourcingAutoConfiguration::class.java,
                ),
            )
            .withPropertyValues(
                "${EventStoreProperties.STORAGE}=${StorageType.ELASTICSEARCH_NAME}",
                "${SnapshotProperties.STORAGE}=${StorageType.MONGO_NAME}",
                "${ElasticsearchProperties.PREFIX}.auto-init-template=false",
                "${ElasticsearchProperties.COMPATIBILITY_VERSION_KEY}=8",
            )
            .run { context ->
                val mediaType = "application/vnd.elasticsearch+json; compatible-with=8"
                val headers = context.getBean(Rest5ClientOptions::class.java).headers().toList()
                headers.first { it.key == "Accept" }.value.assert().isEqualTo(mediaType)
                headers.first { it.key == "Content-Type" }.value.assert().isEqualTo(mediaType)
                context.getBean(Rest5ClientOptions::class.java)
                    .restClientRequestOptions()
                    .httpAsyncResponseConsumerFactory
                    .assert()
                    .isSameAs(SafeResponseConsumer.DEFAULT_FACTORY)
            }
    }

    @Test
    fun `should fail fast when compatibility version is not bound`() {
        val autoConfiguration = ElasticsearchEventSourcingAutoConfiguration(
            elasticsearchProperties = ElasticsearchProperties(),
            eventStoreBatchProperties = ElasticsearchEventStoreBatchProperties(),
            snapshotStoreBatchProperties = ElasticsearchSnapshotStoreBatchProperties(),
        )

        assertThrownBy<IllegalArgumentException> {
            autoConfiguration.rest5ClientOptions()
        }.hasMessage(
            "${ElasticsearchProperties.COMPATIBILITY_VERSION_KEY} must be configured when the compatibility option is enabled",
        )
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
            .withPropertyValues(
                "${ElasticsearchProperties.PREFIX}.event-store-batch.enabled=true",
                "${ElasticsearchProperties.PREFIX}.event-store-batch.max-size=64",
                "${ElasticsearchProperties.PREFIX}.event-store-batch.max-delay=2ms",
                "${ElasticsearchProperties.PREFIX}.event-store-batch.max-pending-appends=2048",
                "${ElasticsearchProperties.PREFIX}.event-store-batch.lane-count=2",
                "${ElasticsearchProperties.PREFIX}.snapshot-store-batch.enabled=true",
                "${ElasticsearchProperties.PREFIX}.snapshot-store-batch.max-size=32",
                "${ElasticsearchProperties.PREFIX}.snapshot-store-batch.max-delay=3ms",
                "${ElasticsearchProperties.PREFIX}.snapshot-store-batch.max-pending-saves=1024",
                "${ElasticsearchProperties.PREFIX}.snapshot-store-batch.lane-count=3",
                "${ElasticsearchQueryProperties.PREFIX}.batch-size=512",
                "${ElasticsearchQueryProperties.PREFIX}.keep-alive=5m",
            )
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
                    .hasSingleBean(ElasticsearchEventStreamQueryBackendFactory::class.java)
                    .hasSingleBean(IndexTemplateInitializer::class.java)
                    .hasBean("elasticsearchSnapshotStore")
                    .doesNotHaveBean("elasticsearchSnapshotRepository")
                    .hasSingleBean(ElasticsearchSnapshotStore::class.java)
                    .hasSingleBean(EventStoreBinding::class.java)
                    .hasSingleBean(SnapshotStoreBinding::class.java)
                    .hasSingleBean(ElasticsearchSnapshotQueryBackendFactory::class.java)
                context.containsBean("snapshotRepository").assert().isFalse()
                assertQueryProperties(context)
                assertBatchOptions(context)
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

    private fun assertQueryProperties(context: AssertableApplicationContext) {
        context.getBean(ElasticsearchQueryProperties::class.java).also {
            it.batchSize.assert().isEqualTo(512)
            it.keepAlive.assert().isEqualTo(java.time.Duration.ofMinutes(5))
        }
    }

    private fun failingQuerySchemaSource(error: Throwable): QuerySchemaSource = object : QuerySchemaSource {
        override val priority: Int = 0

        override fun load(context: QuerySchemaContext): Flux<QuerySchemaDeclaration> = Flux.error(error)
    }

    private fun assertBatchOptions(context: AssertableApplicationContext) {
        val eventOptions = context.getBean(ElasticsearchEventStore::class.java).batchOptions
        eventOptions.enabled.assert().isTrue()
        eventOptions.maxSize.assert().isEqualTo(64)
        eventOptions.maxDelay.assert().isEqualTo(java.time.Duration.ofMillis(2))
        eventOptions.maxPendingAppends.assert().isEqualTo(2048)
        eventOptions.laneCount.assert().isEqualTo(2)

        val snapshotOptions = context.getBean(ElasticsearchSnapshotStore::class.java).batchOptions
        snapshotOptions.enabled.assert().isTrue()
        snapshotOptions.maxSize.assert().isEqualTo(32)
        snapshotOptions.maxDelay.assert().isEqualTo(java.time.Duration.ofMillis(3))
        snapshotOptions.maxPendingSaves.assert().isEqualTo(1024)
        snapshotOptions.laneCount.assert().isEqualTo(3)
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
