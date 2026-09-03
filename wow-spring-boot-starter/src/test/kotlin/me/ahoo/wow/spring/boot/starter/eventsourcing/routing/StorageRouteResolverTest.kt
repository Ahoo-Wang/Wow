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
import me.ahoo.wow.query.event.EventStreamQueryBackendFactory
import me.ahoo.wow.query.event.NoOpEventStreamQueryBackendFactory
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryBackendFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactory
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
    private val mongoEventStreamQueryBackendFactory = NoOpEventStreamQueryBackendFactory
    private val redisEventStreamQueryBackendFactory = RecordingEventStreamQueryBackendFactory()
    private val archiveEventStreamQueryBackendFactory = RecordingEventStreamQueryBackendFactory()
    private val mongoSnapshotQueryBackendFactory = NoOpSnapshotQueryBackendFactory
    private val redisSnapshotQueryBackendFactory = RecordingSnapshotQueryBackendFactory()
    private val archiveSnapshotQueryBackendFactory = RecordingSnapshotQueryBackendFactory()

    @Test
    fun `renamed backend binding keeps public service factory name`() {
        SnapshotQueryBackendFactoryBinding.storage(StorageType.MONGO, mongoSnapshotQueryBackendFactory)
            .name.assert().isEqualTo("mongo-snapshot-query-backend-factory")
        EventStreamQueryBackendFactoryBinding.storage(StorageType.ELASTICSEARCH, mongoEventStreamQueryBackendFactory)
            .name.assert().isEqualTo("elasticsearch-event-stream-query-backend-factory")
    }

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
                            event = StorageChannelRouteProperties(storage = StorageType.ELASTICSEARCH),
                        ),
                    ),
                ),
            )
        }

        exception.message.assert().contains("order")
        exception.message.assert().contains("event")
        exception.message.assert().contains(StorageType.ELASTICSEARCH.name)
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
    fun `event query backend factory routes resolve storage and custom bindings`() {
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

        val resolved = resolver().resolveEventStreamQueryBackendFactoryRoutes(properties)

        resolved.defaultEventStreamQueryBackendFactory.assert().isSameAs(mongoEventStreamQueryBackendFactory)
        resolved.eventStreamQueryBackendFactoryRoutes[MaterializedNamedAggregate("order-service", "order")]
            .assert().isSameAs(redisEventStreamQueryBackendFactory)
        resolved.eventStreamQueryBackendFactoryRoutes[MaterializedNamedAggregate("order-service", "audit")]
            .assert().isSameAs(archiveEventStreamQueryBackendFactory)
    }

    @Test
    fun `snapshot query backend factory routes resolve storage and custom bindings`() {
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

        val resolved = resolver().resolveSnapshotQueryBackendFactoryRoutes(properties)

        resolved.defaultSnapshotQueryBackendFactory.assert().isSameAs(mongoSnapshotQueryBackendFactory)
        resolved.snapshotQueryBackendFactoryRoutes[MaterializedNamedAggregate("order-service", "cart")]
            .assert().isSameAs(redisSnapshotQueryBackendFactory)
        resolved.snapshotQueryBackendFactoryRoutes[MaterializedNamedAggregate("order-service", "audit")]
            .assert().isSameAs(archiveSnapshotQueryBackendFactory)
    }

    @Test
    fun `missing named event query backend factory binding fails`() {
        val properties = StorageRoutingProperties(
            aggregates = mapOf(
                "audit" to AggregateStorageRouteProperties(
                    event = StorageChannelRouteProperties(binding = "archive-event-store"),
                ),
            ),
        )
        val resolver = resolver(includeQueryBackendFactoryBindings = false)

        val exception = assertThrows<IllegalArgumentException> {
            resolver.resolveEventStreamQueryBackendFactoryRoutes(properties)
        }
        exception.message.assert().contains("audit")
        exception.message.assert().contains("archive-event-store")
        exception.message.assert().contains("query backend factory")
    }

    @Test
    fun `missing named snapshot query backend factory binding fails`() {
        val properties = StorageRoutingProperties(
            aggregates = mapOf(
                "audit" to AggregateStorageRouteProperties(
                    snapshot = StorageChannelRouteProperties(binding = "archive-snapshot-store"),
                ),
            ),
        )
        val resolver = resolver(includeQueryBackendFactoryBindings = false)

        val exception = assertThrows<IllegalArgumentException> {
            resolver.resolveSnapshotQueryBackendFactoryRoutes(properties)
        }
        exception.message.assert().contains("audit")
        exception.message.assert().contains("archive-snapshot-store")
        exception.message.assert().contains("query backend factory")
    }

    @Test
    fun `missing storage query backend factory binding fails`() {
        val properties = StorageRoutingProperties(
            aggregates = mapOf(
                "order" to AggregateStorageRouteProperties(
                    event = StorageChannelRouteProperties(storage = StorageType.REDIS),
                ),
            ),
        )
        val resolver = resolver(includeQueryBackendFactoryBindings = false)

        val exception = assertThrows<IllegalArgumentException> {
            resolver.resolveEventStreamQueryBackendFactoryRoutes(properties)
        }
        exception.message.assert().contains("order")
        exception.message.assert().contains(StorageType.REDIS.name)
        exception.message.assert().contains("query backend factory")
    }

    @Test
    fun `missing default query backend factory binding fails`() {
        val resolver = resolver(includeQueryBackendFactoryBindings = false)

        val eventException = assertThrows<IllegalArgumentException> {
            resolver.resolveEventStreamQueryBackendFactoryRoutes(StorageRoutingProperties())
        }
        eventException.message.assert().contains("<default>")
        eventException.message.assert().contains("query backend factory")

        val snapshotException = assertThrows<IllegalArgumentException> {
            resolver.resolveSnapshotQueryBackendFactoryRoutes(StorageRoutingProperties())
        }
        snapshotException.message.assert().contains("<default>")
        snapshotException.message.assert().contains("query backend factory")
    }

    private fun resolver(
        contextName: String = "order-service",
        snapshotEnabled: Boolean = true,
        includeQueryBackendFactoryBindings: Boolean = true
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
            eventStreamQueryBackendFactoryBindings = eventStreamQueryBackendFactoryBindings(
                includeQueryBackendFactoryBindings
            ),
            snapshotQueryBackendFactoryBindings = snapshotQueryBackendFactoryBindings(
                includeQueryBackendFactoryBindings
            ),
        )

    private fun eventStreamQueryBackendFactoryBindings(
        includeQueryBackendFactoryBindings: Boolean
    ): List<EventStreamQueryBackendFactoryBinding> {
        if (!includeQueryBackendFactoryBindings) {
            return emptyList()
        }
        return listOf(
            EventStreamQueryBackendFactoryBinding.storage(
                StorageType.MONGO,
                mongoEventStreamQueryBackendFactory,
            ),
            EventStreamQueryBackendFactoryBinding.storage(
                StorageType.REDIS,
                redisEventStreamQueryBackendFactory,
            ),
            EventStreamQueryBackendFactoryBinding(
                name = "archive-event-store",
                storage = null,
                eventStreamQueryBackendFactory = archiveEventStreamQueryBackendFactory,
            ),
        )
    }

    private fun snapshotQueryBackendFactoryBindings(
        includeQueryBackendFactoryBindings: Boolean
    ): List<SnapshotQueryBackendFactoryBinding> {
        if (!includeQueryBackendFactoryBindings) {
            return emptyList()
        }
        return listOf(
            SnapshotQueryBackendFactoryBinding.storage(
                StorageType.MONGO,
                mongoSnapshotQueryBackendFactory,
            ),
            SnapshotQueryBackendFactoryBinding.storage(
                StorageType.REDIS,
                redisSnapshotQueryBackendFactory,
            ),
            SnapshotQueryBackendFactoryBinding(
                name = "archive-snapshot-store",
                storage = null,
                snapshotQueryBackendFactory = archiveSnapshotQueryBackendFactory,
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
}

private class RecordingEventStreamQueryBackendFactory : EventStreamQueryBackendFactory {
    override fun create(namedAggregate: NamedAggregate) = NoOpEventStreamQueryBackendFactory.create(namedAggregate)
}

private class RecordingSnapshotQueryBackendFactory : SnapshotQueryBackendFactory {
    override fun create(namedAggregate: NamedAggregate) = NoOpSnapshotQueryBackendFactory.create(namedAggregate)
}
