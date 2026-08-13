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

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.backend.QueryBackendFactory
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ResolvedStorageRouteSnapshotTest {
    @Test
    fun `duplicate store logical names fail before route resolution`() {
        val failure = assertThrows<IllegalArgumentException> {
            coordinator(
                eventStores = listOf(
                    EventStoreBinding("event-primary", StorageType.MONGO, mockk()),
                    EventStoreBinding("event-primary", StorageType.REDIS, mockk()),
                ),
            )
        }

        failure.message.assert().contains("event-primary")
    }

    @Test
    fun `duplicate store storage bindings fail before route resolution`() {
        val failure = assertThrows<IllegalArgumentException> {
            coordinator(
                eventStores = listOf(
                    EventStoreBinding("event-primary", StorageType.MONGO, mockk()),
                    EventStoreBinding("event-secondary", StorageType.MONGO, mockk()),
                ),
            )
        }

        failure.message.assert().contains(StorageType.MONGO.name)
    }

    @Test
    fun `duplicate backend logical names fail before route resolution`() {
        val failure = assertThrows<IllegalArgumentException> {
            coordinator(
                backends = listOf(
                    backend("query-primary", QueryDocumentKind.EVENT_STREAM, StorageType.MONGO),
                    backend("query-primary", QueryDocumentKind.SNAPSHOT, StorageType.REDIS),
                ),
            )
        }

        failure.message.assert().contains("query-primary")
    }

    @Test
    fun `duplicate backend storage and document kind fail before route resolution`() {
        val failure = assertThrows<IllegalArgumentException> {
            coordinator(
                backends = listOf(
                    backend("query-primary", QueryDocumentKind.EVENT_STREAM, StorageType.MONGO),
                    backend("query-secondary", QueryDocumentKind.EVENT_STREAM, StorageType.MONGO),
                ),
            )
        }

        failure.message.assert().contains(StorageType.MONGO.name)
        failure.message.assert().contains(QueryDocumentKind.EVENT_STREAM.name)
    }

    @Test
    fun `aggregate aliases resolving to the same query target fail`() {
        val failure = assertThrows<IllegalArgumentException> {
            coordinator().resolve(
                StorageRoutingProperties(
                    aggregates = linkedMapOf(
                        "order" to AggregateStorageRouteProperties(
                            event = StorageChannelRouteProperties(storage = StorageType.REDIS),
                        ),
                        "order-service.order" to AggregateStorageRouteProperties(
                            event = StorageChannelRouteProperties(storage = StorageType.MONGO),
                        ),
                    ),
                ),
            )
        }

        failure.message.assert().contains("order-service.order")
        failure.message.assert().contains("duplicate")
    }

    @Test
    fun `named store route without a matching backend fails`() {
        val failure = assertThrows<IllegalArgumentException> {
            coordinator(
                eventStores = defaultEventStores() +
                    EventStoreBinding("archive-event-store", null, mockk()),
            ).resolve(
                StorageRoutingProperties(
                    aggregates = mapOf(
                        "audit" to AggregateStorageRouteProperties(
                            event = StorageChannelRouteProperties(binding = "archive-event-store"),
                        ),
                    ),
                ),
            )
        }

        failure.message.assert().contains("archive-event-store")
        failure.message.assert().contains("backend")
    }

    @Test
    fun `named route rejects a backend with the wrong document kind`() {
        val failure = assertThrows<IllegalArgumentException> {
            coordinator(
                eventStores = defaultEventStores() +
                    EventStoreBinding("archive-event-store", null, mockk()),
                backends = defaultBackends() +
                    backend("archive-event-store", QueryDocumentKind.SNAPSHOT, null),
            ).resolve(
                StorageRoutingProperties(
                    aggregates = mapOf(
                        "audit" to AggregateStorageRouteProperties(
                            event = StorageChannelRouteProperties(binding = "archive-event-store"),
                        ),
                    ),
                ),
            )
        }

        failure.message.assert().contains("archive-event-store")
        failure.message.assert().contains(QueryDocumentKind.EVENT_STREAM.name)
    }

    @Test
    fun `named route rejects backend storage inconsistent with its store`() {
        val failure = assertThrows<IllegalArgumentException> {
            coordinator(
                eventStores = defaultEventStores() +
                    EventStoreBinding("archive-event-store", null, mockk()),
                backends = defaultBackends() +
                    backend("archive-event-store", QueryDocumentKind.EVENT_STREAM, StorageType.ELASTICSEARCH),
            ).resolve(
                StorageRoutingProperties(
                    aggregates = mapOf(
                        "audit" to AggregateStorageRouteProperties(
                            event = StorageChannelRouteProperties(binding = "archive-event-store"),
                        ),
                    ),
                ),
            )
        }

        failure.message.assert().contains("archive-event-store")
        failure.message.assert().contains("storage")
    }

    @Test
    fun `snapshot route fails when snapshot storage is disabled`() {
        val failure = assertThrows<IllegalStateException> {
            coordinator(snapshotEnabled = false).resolve(
                StorageRoutingProperties(
                    aggregates = mapOf(
                        "cart" to AggregateStorageRouteProperties(
                            snapshot = StorageChannelRouteProperties(storage = StorageType.REDIS),
                        ),
                    ),
                ),
            )
        }

        failure.message.assert().contains("cart")
        failure.message.assert().contains("snapshot")
    }

    @Test
    fun `unavailable storage override never falls back to the default backend`() {
        val snapshot = coordinator(
            backends = defaultBackends().filterNot {
                it.storage == StorageType.REDIS && it.documentKind == QueryDocumentKind.EVENT_STREAM
            },
        ).resolve(
            StorageRoutingProperties(
                aggregates = mapOf(
                    "order" to AggregateStorageRouteProperties(
                        event = StorageChannelRouteProperties(storage = StorageType.REDIS),
                    ),
                ),
            ),
        )
        val backendRoutes = snapshot.queryBackendRoutes()

        backendRoutes.selection(eventTarget("order")).assert().isSameAs(QueryBackendSelection.Unavailable)
        (backendRoutes.selection(eventTarget("cart")) as QueryBackendSelection.Available)
            .binding.storage.assert().isEqualTo(StorageType.MONGO)
    }

    @Test
    fun `store and backend projections share the same canonical named route`() {
        val archiveStore = mockk<EventStore>()
        val archiveBackend = backend("archive-event-store", QueryDocumentKind.EVENT_STREAM, null)
        val snapshot = coordinator(
            eventStores = defaultEventStores() +
                EventStoreBinding("archive-event-store", null, archiveStore),
            backends = defaultBackends() + archiveBackend,
        ).resolve(
            StorageRoutingProperties(
                aggregates = mapOf(
                    "audit" to AggregateStorageRouteProperties(
                        event = StorageChannelRouteProperties(binding = "archive-event-store"),
                    ),
                ),
            ),
        )

        snapshot.eventRoutes().eventRoutes.getValue(AUDIT).assert().isSameAs(archiveStore)
        (snapshot.queryBackendRoutes().selection(eventTarget("audit")) as QueryBackendSelection.Available)
            .binding.assert().isSameAs(archiveBackend)
    }

    @Test
    fun `query route snapshot preserves insertion order and rejects external mutation`() {
        val orderTarget = eventTarget("order")
        val auditTarget = eventTarget("audit")
        val input = linkedMapOf<QueryTarget, QueryBackendSelection>(
            orderTarget to QueryBackendSelection.Unavailable,
            auditTarget to QueryBackendSelection.Unavailable,
        )
        val snapshot = QueryBackendRouteSnapshot(
            defaultSelections = linkedMapOf(QueryDocumentKind.EVENT_STREAM to QueryBackendSelection.Unavailable),
            routeOverrides = input,
        )

        input.clear()
        snapshot.routeOverrides.keys.assert().containsExactly(orderTarget, auditTarget)
        assertThrows<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (snapshot.routeOverrides as MutableMap<QueryTarget, QueryBackendSelection>).clear()
        }
        snapshot.routeOverrides.keys.assert().containsExactly(orderTarget, auditTarget)
    }

    private fun coordinator(
        snapshotEnabled: Boolean = true,
        eventStores: List<EventStoreBinding> = defaultEventStores(),
        backends: List<QueryBackendBinding> = defaultBackends(),
    ): StorageRouteCoordinator = StorageRouteCoordinator(
        contextName = "order-service",
        snapshotEnabled = snapshotEnabled,
        eventStoreBindings = eventStores,
        snapshotStoreBindings = defaultSnapshotStores(),
        eventStreamQueryServiceFactoryBindings = emptyList(),
        snapshotQueryServiceFactoryBindings = emptyList(),
        queryBackendBindings = backends,
        defaultEventStorage = StorageType.MONGO,
        defaultSnapshotStorage = StorageType.MONGO,
    )

    companion object {
        private val AUDIT = MaterializedNamedAggregate("order-service", "audit")

        private fun eventTarget(aggregateName: String): QueryTarget = QueryTarget(
            MaterializedNamedAggregate("order-service", aggregateName),
            QueryDocumentKind.EVENT_STREAM,
        )

        private fun defaultEventStores(): List<EventStoreBinding> = listOf(
            EventStoreBinding.storage(StorageType.MONGO, mockk()),
            EventStoreBinding.storage(StorageType.REDIS, mockk()),
        )

        private fun defaultSnapshotStores(): List<SnapshotStoreBinding> = listOf(
            SnapshotStoreBinding.storage(StorageType.MONGO, mockk<SnapshotStore>()),
            SnapshotStoreBinding.storage(StorageType.REDIS, mockk<SnapshotStore>()),
        )

        private fun defaultBackends(): List<QueryBackendBinding> = listOf(
            backend("mongo-event-store", QueryDocumentKind.EVENT_STREAM, StorageType.MONGO),
            backend("mongo-snapshot-store", QueryDocumentKind.SNAPSHOT, StorageType.MONGO),
            backend("redis-event-store", QueryDocumentKind.EVENT_STREAM, StorageType.REDIS),
            backend("redis-snapshot-store", QueryDocumentKind.SNAPSHOT, StorageType.REDIS),
        )

        private fun backend(
            name: String,
            documentKind: QueryDocumentKind,
            storage: StorageType?,
        ): QueryBackendBinding = QueryBackendBinding(name, documentKind, storage, mockk<QueryBackendFactory>())
    }
}
