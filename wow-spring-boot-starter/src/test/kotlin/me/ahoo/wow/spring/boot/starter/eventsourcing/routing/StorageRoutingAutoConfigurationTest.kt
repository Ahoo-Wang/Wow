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
package me.ahoo.wow.spring.boot.starter.eventsourcing.routing

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.RoutingEventStore
import me.ahoo.wow.eventsourcing.snapshot.RoutingSnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.query.event.EventStreamQueryBackendFactory
import me.ahoo.wow.query.event.NoOpEventStreamQueryBackend
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactory
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.ConditionalOnSnapshotEnabled
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreProperties
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.BeanCreationException
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Configuration
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.mock.env.MockEnvironment
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class StorageRoutingAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(StorageConditionConfiguration::class.java)
    private val routingContextRunner = ApplicationContextRunner()
        .enableWow()
        .withPropertyValues(
            "wow.context-name=order-service",
            "${EventStoreProperties.STORAGE}=${StorageType.MONGO_NAME}",
            "${SnapshotProperties.STORAGE}=${StorageType.MONGO_NAME}",
        )
        .withUserConfiguration(
            StorageRoutingAutoConfiguration::class.java,
            StorageRoutingTestConfiguration::class.java,
        )

    @Test
    fun `default storage should match mongo conditions`() {
        contextRunner
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasBean(MONGO_EVENT_STORE_BEAN)
                    .hasBean(MONGO_SNAPSHOT_STORE_BEAN)
            }
    }

    @Test
    fun `global event storage should match event condition`() {
        contextRunner
            .withPropertyValues("${EventStoreProperties.STORAGE}=${StorageType.IN_MEMORY_NAME}")
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasBean(IN_MEMORY_EVENT_STORE_BEAN)
                context.containsBean(IN_MEMORY_SNAPSHOT_STORE_BEAN).assert().isFalse()
            }
    }

    @Test
    fun `event route storage should match event condition when global event storage differs`() {
        contextRunner
            .withPropertyValues(
                "${EventStoreProperties.STORAGE}=${StorageType.MONGO_NAME}",
                "${StorageRoutingProperties.AGGREGATES}.order.event.storage=${StorageType.REDIS_NAME}",
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasBean(REDIS_EVENT_STORE_BEAN)
                context.containsBean(REDIS_SNAPSHOT_STORE_BEAN).assert().isFalse()
            }
    }

    @Test
    fun `snapshot route storage should match snapshot condition when global snapshot storage differs`() {
        contextRunner
            .withPropertyValues(
                "${SnapshotProperties.STORAGE}=${StorageType.MONGO_NAME}",
                "${StorageRoutingProperties.AGGREGATES}.cart.snapshot.storage=${StorageType.REDIS_NAME}",
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasBean(REDIS_SNAPSHOT_STORE_BEAN)
                context.containsBean(REDIS_EVENT_STORE_BEAN).assert().isFalse()
            }
    }

    @Test
    fun `event route storage should not match snapshot condition`() {
        contextRunner
            .withPropertyValues(
                "${SnapshotProperties.STORAGE}=${StorageType.MONGO_NAME}",
                "${StorageRoutingProperties.AGGREGATES}.order.event.storage=${StorageType.REDIS_NAME}",
            )
            .run { context: AssertableApplicationContext ->
                context.containsBean(REDIS_SNAPSHOT_STORE_BEAN).assert().isFalse()
            }
    }

    @Test
    fun `snapshot route storage should not match event condition`() {
        contextRunner
            .withPropertyValues(
                "${EventStoreProperties.STORAGE}=${StorageType.MONGO_NAME}",
                "${StorageRoutingProperties.AGGREGATES}.cart.snapshot.storage=${StorageType.REDIS_NAME}",
            )
            .run { context: AssertableApplicationContext ->
                context.containsBean(REDIS_EVENT_STORE_BEAN).assert().isFalse()
            }
    }

    @Test
    fun `snapshot storage should not match when snapshot is disabled`() {
        contextRunner
            .withPropertyValues(
                "${ConditionalOnSnapshotEnabled.ENABLED_KEY}=false",
                "${SnapshotProperties.STORAGE}=${StorageType.REDIS_NAME}",
                "${StorageRoutingProperties.AGGREGATES}.cart.snapshot.storage=${StorageType.IN_MEMORY_NAME}",
            )
            .run { context: AssertableApplicationContext ->
                context.containsBean(REDIS_SNAPSHOT_STORE_BEAN).assert().isFalse()
                context.containsBean(IN_MEMORY_SNAPSHOT_STORE_BEAN).assert().isFalse()
            }
    }

    @Test
    fun `storage routing condition should not match without storage annotation`() {
        val metadata = mockk<AnnotatedTypeMetadata> {
            every {
                getAnnotationAttributes(any<String>())
            } returns null
        }

        val outcome = OnStorageRoutingStorageCondition()
            .getMatchOutcome(mockk(), metadata)

        outcome.isMatch.assert().isFalse()
        outcome.message.assert().contains("No storage routing condition annotation")
    }

    @Test
    fun `event storage condition no match message should say does not match`() {
        val context = mockk<ConditionContext> {
            every {
                environment
            } returns MockEnvironment()
                .withProperty(EventStoreProperties.STORAGE, StorageType.MONGO_NAME)
        }
        val metadata = mockk<AnnotatedTypeMetadata> {
            every {
                getAnnotationAttributes(ConditionalOnEventStoreStorage::class.java.name)
            } returns mapOf(VALUE_ATTRIBUTE to StorageType.REDIS)
        }

        val outcome = OnStorageRoutingStorageCondition()
            .getMatchOutcome(context, metadata)

        outcome.isMatch.assert().isFalse()
        outcome.message.assert().contains("does not match")
    }

    @Test
    fun `empty routes should not create routing stores`() {
        routingContextRunner
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .doesNotHaveBean(RoutingEventStore::class.java)
                    .doesNotHaveBean(RoutingSnapshotStore::class.java)
                context.getBean(EventStore::class.java).assert()
                    .isSameAs(context.getBean(RecordingStores::class.java).mongoEventStore)
                context.getBean(SnapshotStore::class.java).assert()
                    .isSameAs(context.getBean(RecordingStores::class.java).mongoSnapshotStore)
            }
    }

    @Test
    fun `event route should create primary routing event store`() {
        routingContextRunner
            .withPropertyValues("${StorageRoutingProperties.AGGREGATES}.order.event.storage=${StorageType.REDIS_NAME}")
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(RoutingEventStore::class.java)
                    .doesNotHaveBean(RoutingSnapshotStore::class.java)

                val stores = context.getBean(RecordingStores::class.java)
                val eventStore = context.getBean(EventStore::class.java)
                eventStore.assert().isSameAs(context.getBean(RoutingEventStore::class.java))
                context.getBean(SnapshotStore::class.java).assert().isSameAs(stores.mongoSnapshotStore)

                StepVerifier.create(eventStore.load(orderAggregateId()))
                    .verifyComplete()
                stores.redisEventStore.lastAggregateId.assert().isEqualTo(orderAggregateId())

                StepVerifier.create(eventStore.load(cartAggregateId()))
                    .verifyComplete()
                stores.mongoEventStore.lastAggregateId.assert().isEqualTo(cartAggregateId())
            }
    }

    @Test
    fun `spring should close a routed event store delegate once`() {
        lateinit var stores: RecordingStores

        routingContextRunner
            .withPropertyValues("${StorageRoutingProperties.AGGREGATES}.order.event.storage=${StorageType.REDIS_NAME}")
            .run { context: AssertableApplicationContext ->
                stores = context.getBean(RecordingStores::class.java)
            }

        stores.mongoEventStore.closeCount.assert().isEqualTo(1)
        stores.redisEventStore.closeCount.assert().isEqualTo(1)
    }

    @Test
    fun `event route should create primary routing event stream query service factory`() {
        routingContextRunner
            .withPropertyValues("${StorageRoutingProperties.AGGREGATES}.order.event.storage=${StorageType.REDIS_NAME}")
            .run { context: AssertableApplicationContext ->
                val stores = context.getBean(RecordingStores::class.java)
                val queryBackendFactory = context.getBean(EventStreamQueryBackendFactory::class.java)

                queryBackendFactory.create(ORDER)
                stores.redisEventStreamQueryBackendFactory.lastNamedAggregate.assert().isEqualTo(ORDER)

                queryBackendFactory.create(CART)
                stores.mongoEventStreamQueryBackendFactory.lastNamedAggregate.assert().isEqualTo(CART)
            }
    }

    @Test
    fun `snapshot route should create primary routing snapshot store`() {
        routingContextRunner
            .withPropertyValues(
                "${StorageRoutingProperties.AGGREGATES}.cart.snapshot.storage=${StorageType.REDIS_NAME}"
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .doesNotHaveBean(RoutingEventStore::class.java)
                    .hasSingleBean(RoutingSnapshotStore::class.java)

                val stores = context.getBean(RecordingStores::class.java)
                context.getBean(EventStore::class.java).assert().isSameAs(stores.mongoEventStore)
                val snapshotStore = context.getBean(SnapshotStore::class.java)
                snapshotStore.assert().isSameAs(context.getBean(RoutingSnapshotStore::class.java))

                StepVerifier.create(snapshotStore.getVersion(cartAggregateId()))
                    .expectNext(7)
                    .verifyComplete()
                stores.redisSnapshotStore.lastAggregateId.assert().isEqualTo(cartAggregateId())

                StepVerifier.create(snapshotStore.getVersion(orderAggregateId()))
                    .expectNext(7)
                    .verifyComplete()
                stores.mongoSnapshotStore.lastAggregateId.assert().isEqualTo(orderAggregateId())
            }
    }

    @Test
    fun `spring should close a routed snapshot store delegate once`() {
        lateinit var stores: RecordingStores

        routingContextRunner
            .withPropertyValues(
                "${StorageRoutingProperties.AGGREGATES}.cart.snapshot.storage=${StorageType.REDIS_NAME}"
            )
            .run { context: AssertableApplicationContext ->
                stores = context.getBean(RecordingStores::class.java)
            }

        stores.mongoSnapshotStore.closeCount.assert().isEqualTo(1)
        stores.redisSnapshotStore.closeCount.assert().isEqualTo(1)
    }

    @Test
    fun `snapshot route should create primary routing snapshot query service factory`() {
        routingContextRunner
            .withPropertyValues(
                "${StorageRoutingProperties.AGGREGATES}.cart.snapshot.storage=${StorageType.REDIS_NAME}"
            )
            .run { context: AssertableApplicationContext ->
                val stores = context.getBean(RecordingStores::class.java)
                val queryBackendFactory = context.getBean(SnapshotQueryBackendFactory::class.java)

                queryBackendFactory.create<Any>(CART)
                stores.redisSnapshotQueryBackendFactory.lastNamedAggregate.assert().isEqualTo(CART)

                queryBackendFactory.create<Any>(ORDER)
                stores.mongoSnapshotQueryBackendFactory.lastNamedAggregate.assert().isEqualTo(ORDER)
            }
    }

    @Test
    fun `custom named bindings should be used by routing stores`() {
        routingContextRunner
            .withPropertyValues(
                "${StorageRoutingProperties.AGGREGATES}.audit.event.binding=archive-event-store",
                "${StorageRoutingProperties.AGGREGATES}.audit.snapshot.binding=archive-snapshot-store",
            )
            .run { context: AssertableApplicationContext ->
                val stores = context.getBean(RecordingStores::class.java)

                StepVerifier.create(context.getBean(EventStore::class.java).load(auditAggregateId()))
                    .verifyComplete()
                stores.archiveEventStore.lastAggregateId.assert().isEqualTo(auditAggregateId())

                StepVerifier.create(context.getBean(SnapshotStore::class.java).getVersion(auditAggregateId()))
                    .expectNext(7)
                    .verifyComplete()
                stores.archiveSnapshotStore.lastAggregateId.assert().isEqualTo(auditAggregateId())
            }
    }

    @Test
    fun `named storage binding without query factory binding should fail startup`() {
        routingContextRunner
            .withPropertyValues(
                "${StorageRoutingProperties.AGGREGATES}.audit.event.binding=store-only-event-store",
            )
            .run { context: AssertableApplicationContext ->
                val failure = context.startupFailure
                failure.assert().isInstanceOf(BeanCreationException::class.java)
                failure!!.message.assert().contains("query service factory")
                failure.message.assert().contains("store-only-event-store")
            }
    }

    @Test
    fun `full aggregate key should not prepend current context`() {
        routingContextRunner
            .withPropertyValues(
                "${StorageRoutingProperties.AGGREGATES}[order-service.order].event.storage=${StorageType.REDIS_NAME}",
            )
            .run { context: AssertableApplicationContext ->
                val stores = context.getBean(RecordingStores::class.java)

                StepVerifier.create(context.getBean(EventStore::class.java).load(orderAggregateId()))
                    .verifyComplete()
                stores.redisEventStore.lastAggregateId.assert().isEqualTo(orderAggregateId())
            }
    }

    @Test
    fun `snapshot route should fail startup when snapshot is disabled`() {
        routingContextRunner
            .withPropertyValues(
                "${ConditionalOnSnapshotEnabled.ENABLED_KEY}=false",
                "${StorageRoutingProperties.AGGREGATES}.cart.snapshot.storage=${StorageType.REDIS_NAME}",
            )
            .run { context: AssertableApplicationContext ->
                val failure = context.startupFailure
                failure.assert().isInstanceOf(BeanCreationException::class.java)
                failure!!.message.assert().contains("snapshot")
            }
    }

    @Configuration(proxyBeanMethods = false)
    internal class StorageConditionConfiguration {
        @Bean(MONGO_EVENT_STORE_BEAN)
        @ConditionalOnEventStoreStorage(StorageType.MONGO)
        fun mongoEventStoreBackend(): String = MONGO_EVENT_STORE_BEAN

        @Bean(IN_MEMORY_EVENT_STORE_BEAN)
        @ConditionalOnEventStoreStorage(StorageType.IN_MEMORY)
        fun inMemoryEventStoreBackend(): String = IN_MEMORY_EVENT_STORE_BEAN

        @Bean(REDIS_EVENT_STORE_BEAN)
        @ConditionalOnEventStoreStorage(StorageType.REDIS)
        fun redisEventStoreBackend(): String = REDIS_EVENT_STORE_BEAN

        @Bean(MONGO_SNAPSHOT_STORE_BEAN)
        @ConditionalOnSnapshotStoreStorage(StorageType.MONGO)
        fun mongoSnapshotStoreBackend(): String = MONGO_SNAPSHOT_STORE_BEAN

        @Bean(IN_MEMORY_SNAPSHOT_STORE_BEAN)
        @ConditionalOnSnapshotStoreStorage(StorageType.IN_MEMORY)
        fun inMemorySnapshotStoreBackend(): String = IN_MEMORY_SNAPSHOT_STORE_BEAN

        @Bean(REDIS_SNAPSHOT_STORE_BEAN)
        @ConditionalOnSnapshotStoreStorage(StorageType.REDIS)
        fun redisSnapshotStoreBackend(): String = REDIS_SNAPSHOT_STORE_BEAN
    }

    @Configuration(proxyBeanMethods = false)
    internal class StorageRoutingTestConfiguration {
        @Bean
        fun recordingStores(): RecordingStores = RecordingStores()

        @Bean
        fun eventStore(stores: RecordingStores): EventStore = stores.mongoEventStore

        @Bean
        fun redisEventStoreLifecycle(stores: RecordingStores): CloseDelegate =
            CloseDelegate(stores.redisEventStore)

        @Bean
        fun snapshotStore(stores: RecordingStores): SnapshotStore = stores.mongoSnapshotStore

        @Bean
        fun redisSnapshotStoreLifecycle(stores: RecordingStores): CloseDelegate =
            CloseDelegate(stores.redisSnapshotStore)

        @Bean
        fun mongoEventStoreBinding(stores: RecordingStores): EventStoreBinding =
            EventStoreBinding.storage(StorageType.MONGO, stores.mongoEventStore)

        @Bean
        fun redisEventStoreBinding(stores: RecordingStores): EventStoreBinding =
            EventStoreBinding.storage(StorageType.REDIS, stores.redisEventStore)

        @Bean
        fun archiveEventStoreBinding(stores: RecordingStores): EventStoreBinding =
            EventStoreBinding(
                name = "archive-event-store",
                storage = null,
                eventStore = stores.archiveEventStore,
            )

        @Bean
        fun storeOnlyEventStoreBinding(stores: RecordingStores): EventStoreBinding =
            EventStoreBinding(
                name = "store-only-event-store",
                storage = null,
                eventStore = stores.archiveEventStore,
            )

        @Bean
        fun mongoSnapshotStoreBinding(stores: RecordingStores): SnapshotStoreBinding =
            SnapshotStoreBinding.storage(StorageType.MONGO, stores.mongoSnapshotStore)

        @Bean
        fun redisSnapshotStoreBinding(stores: RecordingStores): SnapshotStoreBinding =
            SnapshotStoreBinding.storage(StorageType.REDIS, stores.redisSnapshotStore)

        @Bean
        fun archiveSnapshotStoreBinding(stores: RecordingStores): SnapshotStoreBinding =
            SnapshotStoreBinding(
                name = "archive-snapshot-store",
                storage = null,
                snapshotStore = stores.archiveSnapshotStore,
            )

        @Bean
        fun mongoEventStreamQueryBackendFactory(stores: RecordingStores): EventStreamQueryBackendFactory =
            stores.mongoEventStreamQueryBackendFactory

        @Bean
        fun redisEventStreamQueryBackendFactory(stores: RecordingStores): EventStreamQueryBackendFactory =
            stores.redisEventStreamQueryBackendFactory

        @Bean
        fun mongoEventStreamQueryBackendFactoryBinding(stores: RecordingStores): EventStreamQueryBackendFactoryBinding =
            EventStreamQueryBackendFactoryBinding.storage(
                StorageType.MONGO,
                stores.mongoEventStreamQueryBackendFactory
            )

        @Bean
        fun redisEventStreamQueryBackendFactoryBinding(stores: RecordingStores): EventStreamQueryBackendFactoryBinding =
            EventStreamQueryBackendFactoryBinding.storage(
                StorageType.REDIS,
                stores.redisEventStreamQueryBackendFactory
            )

        @Bean
        fun archiveEventStreamQueryBackendFactoryBinding(
            stores: RecordingStores
        ): EventStreamQueryBackendFactoryBinding =
            EventStreamQueryBackendFactoryBinding(
                name = "archive-event-store",
                storage = null,
                eventStreamQueryBackendFactory = stores.archiveEventStreamQueryBackendFactory,
            )

        @Bean
        fun mongoSnapshotQueryBackendFactory(stores: RecordingStores): SnapshotQueryBackendFactory =
            stores.mongoSnapshotQueryBackendFactory

        @Bean
        fun redisSnapshotQueryBackendFactory(stores: RecordingStores): SnapshotQueryBackendFactory =
            stores.redisSnapshotQueryBackendFactory

        @Bean
        fun mongoSnapshotQueryBackendFactoryBinding(stores: RecordingStores): SnapshotQueryBackendFactoryBinding =
            SnapshotQueryBackendFactoryBinding.storage(
                StorageType.MONGO,
                stores.mongoSnapshotQueryBackendFactory
            )

        @Bean
        fun redisSnapshotQueryBackendFactoryBinding(stores: RecordingStores): SnapshotQueryBackendFactoryBinding =
            SnapshotQueryBackendFactoryBinding.storage(
                StorageType.REDIS,
                stores.redisSnapshotQueryBackendFactory
            )

        @Bean
        fun archiveSnapshotQueryBackendFactoryBinding(stores: RecordingStores): SnapshotQueryBackendFactoryBinding =
            SnapshotQueryBackendFactoryBinding(
                name = "archive-snapshot-store",
                storage = null,
                snapshotQueryBackendFactory = stores.archiveSnapshotQueryBackendFactory,
            )
    }

    internal class RecordingStores {
        val mongoEventStore = RecordingEventStore("mongo-event")
        val redisEventStore = RecordingEventStore("redis-event")
        val archiveEventStore = RecordingEventStore("archive-event")
        val mongoSnapshotStore = RecordingSnapshotStore("mongo-snapshot")
        val redisSnapshotStore = RecordingSnapshotStore("redis-snapshot")
        val archiveSnapshotStore = RecordingSnapshotStore("archive-snapshot")
        val mongoEventStreamQueryBackendFactory = RecordingEventStreamQueryBackendFactory()
        val redisEventStreamQueryBackendFactory = RecordingEventStreamQueryBackendFactory()
        val archiveEventStreamQueryBackendFactory = RecordingEventStreamQueryBackendFactory()
        val mongoSnapshotQueryBackendFactory = RecordingSnapshotQueryBackendFactory()
        val redisSnapshotQueryBackendFactory = RecordingSnapshotQueryBackendFactory()
        val archiveSnapshotQueryBackendFactory = RecordingSnapshotQueryBackendFactory()
    }

    internal class CloseDelegate(
        private val delegate: AutoCloseable,
    ) : AutoCloseable {
        override fun close() {
            delegate.close()
        }
    }

    internal class RecordingEventStore(
        val storeName: String
    ) : EventStore {
        var lastAggregateId: AggregateId? = null
        var closeCount: Int = 0

        override fun close() {
            closeCount++
        }

        override fun append(eventStream: DomainEventStream): Mono<Void> {
            lastAggregateId = eventStream.aggregateId
            return Mono.empty()
        }

        override fun load(
            aggregateId: AggregateId,
            headVersion: Int,
            tailVersion: Int
        ): Flux<DomainEventStream> {
            lastAggregateId = aggregateId
            return Flux.empty()
        }

        override fun load(
            aggregateId: AggregateId,
            headEventTime: Long,
            tailEventTime: Long
        ): Flux<DomainEventStream> {
            lastAggregateId = aggregateId
            return Flux.empty()
        }

        override fun last(aggregateId: AggregateId): Mono<DomainEventStream> {
            lastAggregateId = aggregateId
            return Mono.empty()
        }
    }

    internal class RecordingSnapshotStore(
        override val name: String
    ) : SnapshotStore {
        var lastAggregateId: AggregateId? = null
        var closeCount: Int = 0

        override fun close() {
            closeCount++
        }

        override fun <S : Any> load(aggregateId: AggregateId): Mono<Snapshot<S>> {
            lastAggregateId = aggregateId
            return Mono.empty()
        }

        override fun getVersion(aggregateId: AggregateId): Mono<Int> {
            lastAggregateId = aggregateId
            return Mono.just(7)
        }

        override fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void> {
            lastAggregateId = snapshot.aggregateId
            return Mono.empty()
        }
    }

    internal class RecordingEventStreamQueryBackendFactory : EventStreamQueryBackendFactory {
        var lastNamedAggregate: NamedAggregate? = null

        override fun create(namedAggregate: NamedAggregate): NoOpEventStreamQueryBackend {
            lastNamedAggregate = namedAggregate
            return NoOpEventStreamQueryBackend(namedAggregate)
        }
    }

    internal class RecordingSnapshotQueryBackendFactory : SnapshotQueryBackendFactory {
        var lastNamedAggregate: NamedAggregate? = null

        override fun <S : Any> create(namedAggregate: NamedAggregate): NoOpSnapshotQueryBackend {
            lastNamedAggregate = namedAggregate
            return NoOpSnapshotQueryBackend(namedAggregate)
        }
    }

    companion object {
        private val ORDER = MaterializedNamedAggregate("order-service", "order")
        private val CART = MaterializedNamedAggregate("order-service", "cart")
        private val AUDIT = MaterializedNamedAggregate("order-service", "audit")
        private const val MONGO_EVENT_STORE_BEAN = "mongoEventStoreBackend"
        private const val IN_MEMORY_EVENT_STORE_BEAN = "inMemoryEventStoreBackend"
        private const val REDIS_EVENT_STORE_BEAN = "redisEventStoreBackend"
        private const val MONGO_SNAPSHOT_STORE_BEAN = "mongoSnapshotStoreBackend"
        private const val IN_MEMORY_SNAPSHOT_STORE_BEAN = "inMemorySnapshotStoreBackend"
        private const val REDIS_SNAPSHOT_STORE_BEAN = "redisSnapshotStoreBackend"
        private const val VALUE_ATTRIBUTE = "value"

        private fun orderAggregateId(): AggregateId = ORDER.aggregateId("order-1")

        private fun cartAggregateId(): AggregateId = CART.aggregateId("cart-1")

        private fun auditAggregateId(): AggregateId = AUDIT.aggregateId("audit-1")
    }
}
