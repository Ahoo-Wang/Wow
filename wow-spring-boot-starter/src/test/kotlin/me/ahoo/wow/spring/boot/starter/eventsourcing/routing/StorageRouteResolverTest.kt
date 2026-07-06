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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.event.NoOpEventStreamQueryServiceFactory
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class StorageRouteResolverTest {
    private val mongoEventStore = NoOpEventStore()
    private val redisEventStore = NoOpEventStore()
    private val archiveEventStore = NoOpEventStore()
    private val mongoSnapshotStore = NoOpSnapshotStore("mongo")
    private val redisSnapshotStore = NoOpSnapshotStore("redis")
    private val archiveSnapshotStore = NoOpSnapshotStore("archive")
    private val mongoEventStreamQueryServiceFactory = NoOpEventStreamQueryServiceFactory
    private val redisEventStreamQueryServiceFactory = RecordingEventStreamQueryServiceFactory()
    private val archiveEventStreamQueryServiceFactory = RecordingEventStreamQueryServiceFactory()
    private val mongoSnapshotQueryServiceFactory = NoOpSnapshotQueryServiceFactory
    private val redisSnapshotQueryServiceFactory = RecordingSnapshotQueryServiceFactory()
    private val archiveSnapshotQueryServiceFactory = RecordingSnapshotQueryServiceFactory()

    @Test
    fun `aggregate key without context resolves using current context`() {
        val resolved = resolver().resolveEventRoutes(
            StorageRoutingProperties(
                aggregates = mapOf(
                    "order" to AggregateStorageRouteProperties(
                        event = StorageChannelRouteProperties(storage = StorageType.REDIS),
                    ),
                ),
            ),
        )

        resolved.defaultEventStore.assert().isSameAs(mongoEventStore)
        resolved.eventRoutes.keys.single()
            .assert().isEqualTo(MaterializedNamedAggregate("order-service", "order"))
        resolved.eventRoutes.values.single().assert().isSameAs(redisEventStore)
    }

    @Test
    fun `aggregate key with context resolves directly`() {
        val resolved = resolver().resolveEventRoutes(
            StorageRoutingProperties(
                aggregates = mapOf(
                    "order-service.order" to AggregateStorageRouteProperties(
                        event = StorageChannelRouteProperties(storage = StorageType.REDIS),
                    ),
                ),
            ),
        )

        resolved.eventRoutes.keys.single()
            .assert().isEqualTo(MaterializedNamedAggregate("order-service", "order"))
    }

    @Test
    fun `missing route channel is allowed`() {
        val properties = StorageRoutingProperties(
            aggregates = mapOf(
                "cart" to AggregateStorageRouteProperties(
                    snapshot = StorageChannelRouteProperties(storage = StorageType.REDIS),
                ),
            ),
        )

        resolver().resolveEventRoutes(properties).eventRoutes.assert().isEmpty()
        resolver().resolveSnapshotRoutes(properties).snapshotRoutes.keys.single()
            .assert().isEqualTo(MaterializedNamedAggregate("order-service", "cart"))
    }

    @Test
    fun `empty properties produce no resolved routes`() {
        val properties = StorageRoutingProperties()

        resolver().resolveEventRoutes(properties).eventRoutes.assert().isEmpty()
        resolver().resolveSnapshotRoutes(properties).snapshotRoutes.assert().isEmpty()
    }

    @Test
    fun `channel with both storage and binding fails`() {
        val exception = assertThrows<IllegalArgumentException> {
            resolver().resolveEventRoutes(
                StorageRoutingProperties(
                    aggregates = mapOf(
                        "order" to AggregateStorageRouteProperties(
                            event = StorageChannelRouteProperties(
                                storage = StorageType.REDIS,
                                binding = "archive-event-store",
                            ),
                        ),
                    ),
                ),
            )
        }

        exception.message.assert().contains("order")
        exception.message.assert().contains("event")
    }

    @Test
    fun `channel as empty object fails`() {
        val exception = assertThrows<IllegalArgumentException> {
            resolver().resolveEventRoutes(
                StorageRoutingProperties(
                    aggregates = mapOf(
                        "order" to AggregateStorageRouteProperties(
                            event = StorageChannelRouteProperties(),
                        ),
                    ),
                ),
            )
        }

        exception.message.assert().contains("order")
        exception.message.assert().contains("event")
    }

    @Test
    fun `aggregate key with more than one dot fails`() {
        val exception = assertThrows<IllegalArgumentException> {
            resolver().resolveEventRoutes(
                StorageRoutingProperties(
                    aggregates = mapOf(
                        "order-service.sales.order" to AggregateStorageRouteProperties(
                            event = StorageChannelRouteProperties(storage = StorageType.REDIS),
                        ),
                    ),
                ),
            )
        }

        exception.message.assert().contains("order-service.sales.order")
    }

    @Test
    fun `aggregate key without context fails when current context is blank`() {
        val exception = assertThrows<IllegalArgumentException> {
            resolver(contextName = "").resolveEventRoutes(
                StorageRoutingProperties(
                    aggregates = mapOf(
                        "order" to AggregateStorageRouteProperties(
                            event = StorageChannelRouteProperties(storage = StorageType.REDIS),
                        ),
                    ),
                ),
            )
        }

        exception.message.assert().contains("order")
        exception.message.assert().contains("context")
    }

    @Test
    fun `unknown aggregate fails`() {
        val exception = assertThrows<IllegalArgumentException> {
            resolver().resolveEventRoutes(
                StorageRoutingProperties(
                    aggregates = mapOf(
                        "payment" to AggregateStorageRouteProperties(
                            event = StorageChannelRouteProperties(storage = StorageType.REDIS),
                        ),
                    ),
                ),
            )
        }

        exception.message.assert().contains("payment")
    }

    @Test
    fun `unknown event binding fails`() {
        val exception = assertThrows<IllegalArgumentException> {
            resolver().resolveEventRoutes(
                StorageRoutingProperties(
                    aggregates = mapOf(
                        "audit" to AggregateStorageRouteProperties(
                            event = StorageChannelRouteProperties(binding = "missing-event-store"),
                        ),
                    ),
                ),
            )
        }

        exception.message.assert().contains("audit")
        exception.message.assert().contains("event")
        exception.message.assert().contains("missing-event-store")
    }

    @Test
    fun `unknown snapshot binding fails`() {
        val exception = assertThrows<IllegalArgumentException> {
            resolver().resolveSnapshotRoutes(
                StorageRoutingProperties(
                    aggregates = mapOf(
                        "audit" to AggregateStorageRouteProperties(
                            snapshot = StorageChannelRouteProperties(binding = "missing-snapshot-store"),
                        ),
                    ),
                ),
            )
        }

        exception.message.assert().contains("audit")
        exception.message.assert().contains("snapshot")
        exception.message.assert().contains("missing-snapshot-store")
    }

    @Test
    fun `missing storage type binding fails for configured channel`() {
        val exception = assertThrows<IllegalArgumentException> {
            resolver().resolveEventRoutes(
                StorageRoutingProperties(
                    aggregates = mapOf(
                        "order" to AggregateStorageRouteProperties(
                            event = StorageChannelRouteProperties(storage = StorageType.R2DBC),
                        ),
                    ),
                ),
            )
        }

        exception.message.assert().contains("order")
        exception.message.assert().contains("event")
        exception.message.assert().contains(StorageType.R2DBC.name)
    }

    @Test
    fun `snapshot disabled plus any snapshot route fails`() {
        val exception = assertThrows<IllegalStateException> {
            resolver(snapshotEnabled = false).resolveSnapshotRoutes(
                StorageRoutingProperties(
                    aggregates = mapOf(
                        "cart" to AggregateStorageRouteProperties(
                            snapshot = StorageChannelRouteProperties(storage = StorageType.REDIS),
                        ),
                    ),
                ),
            )
        }

        exception.message.assert().contains("cart")
        exception.message.assert().contains("snapshot")
    }

    @Test
    fun `event and snapshot binding routes resolve custom named bindings`() {
        val properties = StorageRoutingProperties(
            aggregates = mapOf(
                "audit" to AggregateStorageRouteProperties(
                    event = StorageChannelRouteProperties(binding = "archive-event-store"),
                    snapshot = StorageChannelRouteProperties(binding = "archive-snapshot-store"),
                ),
            ),
        )

        resolver().resolveEventRoutes(properties).eventRoutes.values.single()
            .assert().isSameAs(archiveEventStore)
        resolver().resolveSnapshotRoutes(properties).snapshotRoutes.values.single()
            .assert().isSameAs(archiveSnapshotStore)
    }

    @Test
    fun `event query service factory routes resolve storage and custom bindings`() {
        val properties = StorageRoutingProperties(
            aggregates = mapOf(
                "order" to AggregateStorageRouteProperties(
                    event = StorageChannelRouteProperties(storage = StorageType.REDIS),
                ),
                "audit" to AggregateStorageRouteProperties(
                    event = StorageChannelRouteProperties(binding = "archive-event-store"),
                ),
            ),
        )

        val resolved = resolver().resolveEventStreamQueryServiceFactoryRoutes(properties)

        resolved.defaultEventStreamQueryServiceFactory.assert().isSameAs(mongoEventStreamQueryServiceFactory)
        resolved.eventStreamQueryServiceFactoryRoutes[MaterializedNamedAggregate("order-service", "order")]
            .assert().isSameAs(redisEventStreamQueryServiceFactory)
        resolved.eventStreamQueryServiceFactoryRoutes[MaterializedNamedAggregate("order-service", "audit")]
            .assert().isSameAs(archiveEventStreamQueryServiceFactory)
    }

    @Test
    fun `snapshot query service factory routes resolve storage and custom bindings`() {
        val properties = StorageRoutingProperties(
            aggregates = mapOf(
                "cart" to AggregateStorageRouteProperties(
                    snapshot = StorageChannelRouteProperties(storage = StorageType.REDIS),
                ),
                "audit" to AggregateStorageRouteProperties(
                    snapshot = StorageChannelRouteProperties(binding = "archive-snapshot-store"),
                ),
            ),
        )

        val resolved = resolver().resolveSnapshotQueryServiceFactoryRoutes(properties)

        resolved.defaultSnapshotQueryServiceFactory.assert().isSameAs(mongoSnapshotQueryServiceFactory)
        resolved.snapshotQueryServiceFactoryRoutes[MaterializedNamedAggregate("order-service", "cart")]
            .assert().isSameAs(redisSnapshotQueryServiceFactory)
        resolved.snapshotQueryServiceFactoryRoutes[MaterializedNamedAggregate("order-service", "audit")]
            .assert().isSameAs(archiveSnapshotQueryServiceFactory)
    }

    @Test
    fun `missing query service factory bindings fall back to noop factories`() {
        val properties = StorageRoutingProperties(
            aggregates = mapOf(
                "audit" to AggregateStorageRouteProperties(
                    event = StorageChannelRouteProperties(binding = "archive-event-store"),
                    snapshot = StorageChannelRouteProperties(binding = "archive-snapshot-store"),
                ),
            ),
        )
        val resolver = resolver(includeQueryServiceFactoryBindings = false)

        resolver.resolveEventStreamQueryServiceFactoryRoutes(properties)
            .eventStreamQueryServiceFactoryRoutes.values.single()
            .assert().isSameAs(NoOpEventStreamQueryServiceFactory)
        resolver.resolveSnapshotQueryServiceFactoryRoutes(properties)
            .snapshotQueryServiceFactoryRoutes.values.single()
            .assert().isSameAs(NoOpSnapshotQueryServiceFactory)
    }

    @Test
    fun `missing default query service factory bindings fall back to noop factories`() {
        val resolver = resolver(includeQueryServiceFactoryBindings = false)

        resolver.resolveEventStreamQueryServiceFactoryRoutes(StorageRoutingProperties())
            .defaultEventStreamQueryServiceFactory.assert().isSameAs(NoOpEventStreamQueryServiceFactory)
        resolver.resolveSnapshotQueryServiceFactoryRoutes(StorageRoutingProperties())
            .defaultSnapshotQueryServiceFactory.assert().isSameAs(NoOpSnapshotQueryServiceFactory)
    }

    private fun resolver(
        contextName: String = "order-service",
        snapshotEnabled: Boolean = true,
        includeQueryServiceFactoryBindings: Boolean = true
    ): StorageRouteResolver =
        StorageRouteResolver(
            contextName = contextName,
            snapshotEnabled = snapshotEnabled,
            eventStoreBindings = listOf(
                EventStoreBinding.storage(StorageType.MONGO, mongoEventStore),
                EventStoreBinding.storage(StorageType.REDIS, redisEventStore),
                EventStoreBinding(
                    name = "archive-event-store",
                    storage = null,
                    eventStore = archiveEventStore,
                ),
            ),
            snapshotStoreBindings = listOf(
                SnapshotStoreBinding.storage(StorageType.MONGO, mongoSnapshotStore),
                SnapshotStoreBinding.storage(StorageType.REDIS, redisSnapshotStore),
                SnapshotStoreBinding(
                    name = "archive-snapshot-store",
                    storage = null,
                    snapshotStore = archiveSnapshotStore,
                ),
            ),
            eventStreamQueryServiceFactoryBindings = eventStreamQueryServiceFactoryBindings(
                includeQueryServiceFactoryBindings
            ),
            snapshotQueryServiceFactoryBindings = snapshotQueryServiceFactoryBindings(
                includeQueryServiceFactoryBindings
            ),
        )

    private fun eventStreamQueryServiceFactoryBindings(
        includeQueryServiceFactoryBindings: Boolean
    ): List<EventStreamQueryServiceFactoryBinding> {
        if (!includeQueryServiceFactoryBindings) {
            return emptyList()
        }
        return listOf(
            EventStreamQueryServiceFactoryBinding.storage(
                StorageType.MONGO,
                mongoEventStreamQueryServiceFactory,
            ),
            EventStreamQueryServiceFactoryBinding.storage(
                StorageType.REDIS,
                redisEventStreamQueryServiceFactory,
            ),
            EventStreamQueryServiceFactoryBinding(
                name = "archive-event-store",
                storage = null,
                eventStreamQueryServiceFactory = archiveEventStreamQueryServiceFactory,
            ),
        )
    }

    private fun snapshotQueryServiceFactoryBindings(
        includeQueryServiceFactoryBindings: Boolean
    ): List<SnapshotQueryServiceFactoryBinding> {
        if (!includeQueryServiceFactoryBindings) {
            return emptyList()
        }
        return listOf(
            SnapshotQueryServiceFactoryBinding.storage(
                StorageType.MONGO,
                mongoSnapshotQueryServiceFactory,
            ),
            SnapshotQueryServiceFactoryBinding.storage(
                StorageType.REDIS,
                redisSnapshotQueryServiceFactory,
            ),
            SnapshotQueryServiceFactoryBinding(
                name = "archive-snapshot-store",
                storage = null,
                snapshotQueryServiceFactory = archiveSnapshotQueryServiceFactory,
            ),
        )
    }
}

class OrderAggregate(val id: String)

class CartAggregate(val id: String)

class AuditAggregate(val id: String)

private class NoOpEventStore : EventStore {
    override fun append(eventStream: DomainEventStream): Mono<Void> = Mono.empty()

    override fun load(aggregateId: AggregateId, headVersion: Int, tailVersion: Int): Flux<DomainEventStream> =
        Flux.empty()

    override fun load(aggregateId: AggregateId, headEventTime: Long, tailEventTime: Long): Flux<DomainEventStream> =
        Flux.empty()

    override fun last(aggregateId: AggregateId): Mono<DomainEventStream> = Mono.empty()
}

private class NoOpSnapshotStore(
    override val name: String
) : SnapshotStore {
    override fun <S : Any> load(aggregateId: AggregateId): Mono<Snapshot<S>> = Mono.empty()

    override fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void> = Mono.empty()

    override fun scanAggregateId(
        namedAggregate: NamedAggregate,
        afterId: String,
        limit: Int
    ): Flux<AggregateId> = Flux.empty()
}

private class RecordingEventStreamQueryServiceFactory : EventStreamQueryServiceFactory {
    override fun create(namedAggregate: NamedAggregate): EventStreamQueryService {
        throw UnsupportedOperationException("Not needed for route resolution tests.")
    }
}

private class RecordingSnapshotQueryServiceFactory : SnapshotQueryServiceFactory {
    override fun <S : Any> create(namedAggregate: NamedAggregate): SnapshotQueryService<S> {
        throw UnsupportedOperationException("Not needed for route resolution tests.")
    }
}
