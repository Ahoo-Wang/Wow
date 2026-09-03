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

package me.ahoo.wow.spring.boot.starter.mongo

import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.reactivestreams.client.FindPublisher
import com.mongodb.reactivestreams.client.ListCollectionNamesPublisher
import com.mongodb.reactivestreams.client.MongoClient
import com.mongodb.reactivestreams.client.MongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import me.ahoo.test.asserts.assert
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.configuration.NamedAggregateTypeSearcher
import me.ahoo.wow.metrics.WowMetrics
import me.ahoo.wow.mongo.MongoDatabaseContextGuard
import me.ahoo.wow.mongo.MongoEventStore
import me.ahoo.wow.mongo.MongoSnapshotStore
import me.ahoo.wow.mongo.MongoSnapshotStoreBatchOptions
import me.ahoo.wow.mongo.prepare.MongoPrepareKeyFactory
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.EventStoreBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.SnapshotStoreBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreProperties
import me.ahoo.wow.spring.boot.starter.query.QuerySchemaAutoConfiguration
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.bson.Document
import org.bson.conversions.Bson
import org.junit.jupiter.api.Test
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import reactor.core.publisher.Flux
import reactor.kotlin.test.test

class MongoEventSourcingAutoConfigurationTest {
    private val metricsProvider = StaticListableBeanFactory(
        mapOf("wowMetrics" to WowMetrics.NONE)
    ).getBeanProvider(WowMetrics::class.java)
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(QuerySchemaAutoConfiguration::class.java)

    @Test
    fun `event stream query factory should use default schema collaborators`() {
        MongoEventSourcingAutoConfiguration(
            mongoProperties = MongoProperties(autoInitSchema = false, eventStreamDatabase = "testEventStream"),
            eventStoreBatchProperties = MongoEventStoreBatchProperties(),
            snapshotStoreBatchProperties = MongoSnapshotStoreBatchProperties(),
        ).mongoEventStreamQueryBackendFactory(
            mongoClient = mongoClient("order-service"),
            dataMongoProperties = null,
            currentBoundedContext = MaterializedNamedBoundedContext("order-service"),
        ).assert().isInstanceOf(me.ahoo.wow.mongo.query.event.MongoEventStreamQueryBackendFactory::class.java)
    }

    @Test
    fun `constructor creates mongo snapshot store`() {
        val configuration = MongoEventSourcingAutoConfiguration(
            mongoProperties = MongoProperties(
                autoInitSchema = true,
                eventStreamDatabase = null,
                snapshotDatabase = "testSnapshot",
                prepareDatabase = null,
            ),
            eventStoreBatchProperties = MongoEventStoreBatchProperties(),
            snapshotStoreBatchProperties = MongoSnapshotStoreBatchProperties(),
        )

        withEmptyAggregateMetadata {
            val snapshotStore = configuration.mongoSnapshotStore(
                mongoClient = mongoClient("order-service"),
                dataMongoProperties = null,
                currentBoundedContext = MaterializedNamedBoundedContext("order-service"),
                metrics = metricsProvider,
            )
            snapshotStore.assert().isInstanceOf(MongoSnapshotStore::class.java)
            snapshotStore.batchOptions.assert().isEqualTo(MongoSnapshotStoreBatchOptions())
        }
    }

    @Test
    fun `should pass query schema sources to snapshot factory`() {
        val expected = IllegalStateException("query schema source was used")
        val source = failingQuerySchemaSource(expected)
        val configuration = MongoEventSourcingAutoConfiguration(
            mongoProperties = MongoProperties(autoInitSchema = false, snapshotDatabase = "testSnapshot"),
            eventStoreBatchProperties = MongoEventStoreBatchProperties(),
            snapshotStoreBatchProperties = MongoSnapshotStoreBatchProperties(),
        )

        val factory = configuration.mongoSnapshotQueryBackendFactory(
            mongoClient = mongoClient("order-service"),
            dataMongoProperties = null,
            currentBoundedContext = MaterializedNamedBoundedContext("order-service"),
            sources = listOf(source),
        )

        factory.create(MOCK_AGGREGATE_METADATA).schemaProvider
            .schema()
            .test()
            .expectErrorSatisfies { it.assert().isSameAs(expected) }
            .verify()
    }

    @Test
    fun `should pass query schema sources to event stream factory`() {
        val expected = IllegalStateException("query schema source was used")
        val configuration = MongoEventSourcingAutoConfiguration(
            mongoProperties = MongoProperties(autoInitSchema = false, eventStreamDatabase = "testEventStream"),
            eventStoreBatchProperties = MongoEventStoreBatchProperties(),
            snapshotStoreBatchProperties = MongoSnapshotStoreBatchProperties(),
        )

        val factory = configuration.mongoEventStreamQueryBackendFactory(
            mongoClient = mongoClient("order-service"),
            dataMongoProperties = null,
            currentBoundedContext = MaterializedNamedBoundedContext("order-service"),
            sources = listOf(failingQuerySchemaSource(expected)),
        )

        factory.create(MOCK_AGGREGATE_METADATA).schemaProvider
            .schema()
            .test()
            .expectErrorSatisfies { it.assert().isSameAs(expected) }
            .verify()
    }

    @Test
    fun `should load context with mongo event sourcing beans`() {
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${MongoProperties.PREFIX}.event-stream-database=testEventStream",
                "${MongoProperties.PREFIX}.snapshot-database=testSnapshot",
                "${MongoProperties.PREFIX}.prepare-database=testPrepare",
                "${MongoProperties.PREFIX}.error-database=testError",
                "${MongoProperties.PREFIX}.auto-init-schema=false",
                "${MongoProperties.PREFIX}.event-store-batch.enabled=true",
                "${MongoProperties.PREFIX}.event-store-batch.max-size=64",
                "${MongoProperties.PREFIX}.event-store-batch.max-delay=2ms",
                "${MongoProperties.PREFIX}.event-store-batch.max-pending-appends=2048",
                "${MongoProperties.PREFIX}.event-store-batch.lane-count=2",
                "${MongoProperties.PREFIX}.snapshot-store-batch.enabled=true",
                "${MongoProperties.PREFIX}.snapshot-store-batch.max-size=32",
                "${MongoProperties.PREFIX}.snapshot-store-batch.max-delay=3ms",
                "${MongoProperties.PREFIX}.snapshot-store-batch.max-pending-saves=1024",
                "${MongoProperties.PREFIX}.snapshot-store-batch.lane-count=3",
                "wow.context-name=order-service",
            )
            .withBean(MongoClient::class.java, {
                mongoClient("order-service")
            })
            .withUserConfiguration(
                MongoEventSourcingAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(MongoEventStore::class.java)
                    .hasBean("mongoSnapshotStore")
                    .doesNotHaveBean("mongoSnapshotRepository")
                    .hasSingleBean(MongoSnapshotStore::class.java)
                    .hasSingleBean(EventStoreBinding::class.java)
                    .hasSingleBean(SnapshotStoreBinding::class.java)
                    .hasSingleBean(MongoPrepareKeyFactory::class.java)
                val eventStore = context.getBean(MongoEventStore::class.java)
                eventStore.batchOptions.enabled.assert().isTrue()
                eventStore.batchOptions.maxSize.assert().isEqualTo(64)
                eventStore.batchOptions.maxDelay.assert().isEqualTo(java.time.Duration.ofMillis(2))
                eventStore.batchOptions.maxPendingAppends.assert().isEqualTo(2048)
                eventStore.batchOptions.laneCount.assert().isEqualTo(2)
                val eventBinding = context.getBean(EventStoreBinding::class.java)
                eventBinding.storage.assert().isEqualTo(StorageType.MONGO)
                eventBinding.eventStore.assert().isSameAs(eventStore)

                val snapshotStore = context.getBean(MongoSnapshotStore::class.java)
                snapshotStore.batchOptions.enabled.assert().isTrue()
                snapshotStore.batchOptions.maxSize.assert().isEqualTo(32)
                snapshotStore.batchOptions.maxDelay.assert().isEqualTo(java.time.Duration.ofMillis(3))
                snapshotStore.batchOptions.maxPendingSaves.assert().isEqualTo(1024)
                snapshotStore.batchOptions.laneCount.assert().isEqualTo(3)
                val snapshotBinding = context.getBean(SnapshotStoreBinding::class.java)
                snapshotBinding.storage.assert().isEqualTo(StorageType.MONGO)
                snapshotBinding.snapshotStore.assert().isSameAs(snapshotStore)
            }
    }

    @Test
    fun `should reject a database owned by another context when auto init is disabled`() {
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${MongoProperties.PREFIX}.event-stream-database=testEventStream",
                "${MongoProperties.PREFIX}.snapshot-database=testSnapshot",
                "${MongoProperties.PREFIX}.prepare-database=testPrepare",
                "${MongoProperties.PREFIX}.auto-init-schema=false",
                "wow.context-name=payment-service",
            )
            .withBean(MongoClient::class.java, {
                mongoClient("order-service")
            })
            .withUserConfiguration(MongoEventSourcingAutoConfiguration::class.java)
            .run { context ->
                context.startupFailure.assert().isNotNull()
                generateSequence(context.startupFailure) { error -> error.cause }
                    .mapNotNull(Throwable::message)
                    .toList()
                    .assert()
                    .anyMatch {
                        it.contains("order-service") &&
                            it.contains("payment-service") &&
                            it.contains("one bounded context per MongoDB database")
                    }
            }
    }

    @Test
    fun `should guard a dedicated prepare database when event and snapshot use other storage`() {
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${MongoProperties.PREFIX}.prepare-database=testPrepare",
                "${EventStoreProperties.STORAGE}=${StorageType.IN_MEMORY_NAME}",
                "${SnapshotProperties.STORAGE}=${StorageType.IN_MEMORY_NAME}",
                "wow.context-name=payment-service",
            )
            .withBean(MongoClient::class.java, {
                mongoClient("order-service")
            })
            .withUserConfiguration(MongoEventSourcingAutoConfiguration::class.java)
            .run { context ->
                context.startupFailure.assert().isNotNull()
                generateSequence(context.startupFailure) { error -> error.cause }
                    .mapNotNull(Throwable::message)
                    .toList()
                    .assert()
                    .anyMatch {
                        it.contains("order-service") &&
                            it.contains("payment-service") &&
                            it.contains("one bounded context per MongoDB database")
                    }
            }
    }

    private fun mongoClient(ownerContextName: String): MongoClient {
        val marker = Document()
            .append(MessageRecords.CONTEXT_NAME, ownerContextName)
            .append(MongoDatabaseContextGuard.LAYOUT_VERSION_FIELD, 1)
        val markerCollection = mockk<MongoCollection<Document>>()
        val markerPublisher = mockk<FindPublisher<Document>>()
        every { markerCollection.find(any<Bson>()) } returns markerPublisher
        every { markerPublisher.first() } returns publisherOf(marker)
        every {
            markerCollection.findOneAndUpdate(
                any<Bson>(),
                any<Bson>(),
                any<FindOneAndUpdateOptions>(),
            )
        } returns publisherOf(marker)

        val database = mockk<MongoDatabase>()
        every { database.name } returns "testDatabase"
        every { database.listCollectionNames() } returns emptyCollectionNamesPublisher()
        every { database.getCollection(any()) } returns markerCollection

        return mockk<MongoClient> {
            every { getDatabase(any()) } returns database
        }
    }

    private fun failingQuerySchemaSource(error: Throwable): QuerySchemaSource = object : QuerySchemaSource {
        override val priority: Int = 0

        override fun load(context: QuerySchemaContext): Flux<QuerySchemaDeclaration> = Flux.error(error)
    }

    private fun withEmptyAggregateMetadata(block: () -> Unit) {
        mockkObject(MetadataSearcher)
        try {
            every { MetadataSearcher.namedAggregateType } returns NamedAggregateTypeSearcher(emptyMap())
            block()
        } finally {
            unmockkObject(MetadataSearcher)
        }
    }

    private fun emptyCollectionNamesPublisher(): ListCollectionNamesPublisher {
        return mockk {
            every { subscribe(any()) } answers {
                firstArg<Subscriber<in String>>().complete()
            }
        }
    }

    private fun <T> publisherOf(value: T): Publisher<T> {
        return Publisher { subscriber ->
            subscriber.onSubscribe(
                object : Subscription {
                    private var emitted = false

                    override fun request(numberOfElements: Long) {
                        if (emitted) {
                            return
                        }
                        emitted = true
                        subscriber.onNext(value)
                        subscriber.onComplete()
                    }

                    override fun cancel() = Unit
                },
            )
        }
    }

    private fun <T> Subscriber<in T>.complete() {
        onSubscribe(
            object : Subscription {
                override fun request(numberOfElements: Long) {
                    onComplete()
                }

                override fun cancel() = Unit
            },
        )
    }
}
