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

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.event.NoOpEventStreamQueryServiceFactory
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType

class StorageRouteResolver(
    private val contextName: String,
    private val snapshotEnabled: Boolean,
    eventStoreBindings: List<EventStoreBinding>,
    snapshotStoreBindings: List<SnapshotStoreBinding>,
    eventStreamQueryServiceFactoryBindings: List<EventStreamQueryServiceFactoryBinding> = emptyList(),
    snapshotQueryServiceFactoryBindings: List<SnapshotQueryServiceFactoryBinding> = emptyList(),
    private val defaultEventStorage: StorageType = StorageType.MONGO,
    private val defaultSnapshotStorage: StorageType = StorageType.MONGO
) {
    private val eventStoreBindingsByName: Map<String, EventStoreBinding> =
        eventStoreBindings.associateBy { it.name }
    private val eventStoreBindingsByStorage: Map<StorageType, EventStoreBinding> =
        eventStoreBindings.mapNotNull { binding ->
            binding.storage?.let { storage ->
                storage to binding
            }
        }.toMap()
    private val snapshotStoreBindingsByName: Map<String, SnapshotStoreBinding> =
        snapshotStoreBindings.associateBy { it.name }
    private val snapshotStoreBindingsByStorage: Map<StorageType, SnapshotStoreBinding> =
        snapshotStoreBindings.mapNotNull { binding ->
            binding.storage?.let { storage ->
                storage to binding
            }
        }.toMap()
    private val eventStreamQueryServiceFactoryBindingsByName: Map<String, EventStreamQueryServiceFactoryBinding> =
        eventStreamQueryServiceFactoryBindings.associateBy { it.name }
    private val eventStreamQueryServiceFactoryBindingsByStorage:
        Map<StorageType, EventStreamQueryServiceFactoryBinding> =
        eventStreamQueryServiceFactoryBindings.mapNotNull { binding ->
            binding.storage?.let { storage ->
                storage to binding
            }
        }.toMap()
    private val snapshotQueryServiceFactoryBindingsByName: Map<String, SnapshotQueryServiceFactoryBinding> =
        snapshotQueryServiceFactoryBindings.associateBy { it.name }
    private val snapshotQueryServiceFactoryBindingsByStorage: Map<StorageType, SnapshotQueryServiceFactoryBinding> =
        snapshotQueryServiceFactoryBindings.mapNotNull { binding ->
            binding.storage?.let { storage ->
                storage to binding
            }
        }.toMap()

    fun resolveEventRoutes(properties: StorageRoutingProperties): ResolvedEventRoutes {
        val routes: Map<NamedAggregate, EventStore> = properties.aggregates.mapNotNull { (routeKey, aggregateRoute) ->
            val channel = aggregateRoute.event ?: return@mapNotNull null
            val namedAggregate = resolveStorageNamedAggregate(contextName, routeKey)
            namedAggregate to resolveEventStore(routeKey, channel)
        }.toMap()
        return ResolvedEventRoutes(
            defaultEventStore = requiredEventStore(defaultEventStorage, "<default>", EVENT_CHANNEL),
            eventRoutes = routes,
        )
    }

    fun resolveSnapshotRoutes(properties: StorageRoutingProperties): ResolvedSnapshotRoutes {
        validateSnapshotRouting(snapshotEnabled, properties, SNAPSHOT_CHANNEL)

        val routes: Map<NamedAggregate, SnapshotStore> = properties.aggregates.mapNotNull { (routeKey, aggregateRoute) ->
            val channel = aggregateRoute.snapshot ?: return@mapNotNull null
            val namedAggregate = resolveStorageNamedAggregate(contextName, routeKey)
            namedAggregate to resolveSnapshotStore(routeKey, channel)
        }.toMap()
        return ResolvedSnapshotRoutes(
            defaultSnapshotStore = requiredSnapshotStore(defaultSnapshotStorage, "<default>", SNAPSHOT_CHANNEL),
            snapshotRoutes = routes,
        )
    }

    fun resolveEventStreamQueryServiceFactoryRoutes(
        properties: StorageRoutingProperties
    ): ResolvedEventStreamQueryServiceFactoryRoutes {
        val routes: Map<NamedAggregate, EventStreamQueryServiceFactory> =
            properties.aggregates.mapNotNull { (routeKey, aggregateRoute) ->
                val channel = aggregateRoute.event ?: return@mapNotNull null
                val namedAggregate = resolveStorageNamedAggregate(contextName, routeKey)
                namedAggregate to resolveEventStreamQueryServiceFactory(routeKey, channel)
            }.toMap()
        return ResolvedEventStreamQueryServiceFactoryRoutes(
            defaultEventStreamQueryServiceFactory = eventStreamQueryServiceFactory(defaultEventStorage),
            eventStreamQueryServiceFactoryRoutes = routes,
        )
    }

    fun resolveSnapshotQueryServiceFactoryRoutes(
        properties: StorageRoutingProperties
    ): ResolvedSnapshotQueryServiceFactoryRoutes {
        val routes: Map<NamedAggregate, SnapshotQueryServiceFactory> =
            properties.aggregates.mapNotNull { (routeKey, aggregateRoute) ->
                val channel = aggregateRoute.snapshot ?: return@mapNotNull null
                val namedAggregate = resolveStorageNamedAggregate(contextName, routeKey)
                namedAggregate to resolveSnapshotQueryServiceFactory(routeKey, channel)
            }.toMap()
        return ResolvedSnapshotQueryServiceFactoryRoutes(
            defaultSnapshotQueryServiceFactory = snapshotQueryServiceFactory(defaultSnapshotStorage),
            snapshotQueryServiceFactoryRoutes = routes,
        )
    }

    private fun resolveEventStore(routeKey: String, channel: StorageChannelRouteProperties): EventStore {
        validateStorageChannel(routeKey, EVENT_CHANNEL, channel)
        channel.storage?.let { storage ->
            return requiredEventStore(storage, routeKey, EVENT_CHANNEL)
        }
        val binding = channel.binding!!.trim()
        return requireNotNull(eventStoreBindingsByName[binding]?.eventStore) {
            "Storage route[$routeKey] channel[$EVENT_CHANNEL] binding[$binding] was not found."
        }
    }

    private fun resolveSnapshotStore(routeKey: String, channel: StorageChannelRouteProperties): SnapshotStore {
        validateStorageChannel(routeKey, SNAPSHOT_CHANNEL, channel)
        channel.storage?.let { storage ->
            return requiredSnapshotStore(storage, routeKey, SNAPSHOT_CHANNEL)
        }
        val binding = channel.binding!!.trim()
        return requireNotNull(snapshotStoreBindingsByName[binding]?.snapshotStore) {
            "Storage route[$routeKey] channel[$SNAPSHOT_CHANNEL] binding[$binding] was not found."
        }
    }

    private fun resolveEventStreamQueryServiceFactory(
        routeKey: String,
        channel: StorageChannelRouteProperties
    ): EventStreamQueryServiceFactory {
        validateStorageChannel(routeKey, EVENT_CHANNEL, channel)
        channel.storage?.let { storage ->
            return eventStreamQueryServiceFactory(storage)
        }
        val binding = channel.binding!!.trim()
        return eventStreamQueryServiceFactoryBindingsByName[binding]?.eventStreamQueryServiceFactory
            ?: NoOpEventStreamQueryServiceFactory
    }

    private fun resolveSnapshotQueryServiceFactory(
        routeKey: String,
        channel: StorageChannelRouteProperties
    ): SnapshotQueryServiceFactory {
        validateStorageChannel(routeKey, SNAPSHOT_CHANNEL, channel)
        channel.storage?.let { storage ->
            return snapshotQueryServiceFactory(storage)
        }
        val binding = channel.binding!!.trim()
        return snapshotQueryServiceFactoryBindingsByName[binding]?.snapshotQueryServiceFactory
            ?: NoOpSnapshotQueryServiceFactory
    }

    private fun requiredEventStore(
        storage: StorageType,
        routeKey: String,
        channelName: String
    ): EventStore =
        requireNotNull(eventStoreBindingsByStorage[storage]?.eventStore) {
            "Storage route[$routeKey] channel[$channelName] storage[${storage.name}] was not found."
        }

    private fun requiredSnapshotStore(
        storage: StorageType,
        routeKey: String,
        channelName: String
    ): SnapshotStore =
        requireNotNull(snapshotStoreBindingsByStorage[storage]?.snapshotStore) {
            "Storage route[$routeKey] channel[$channelName] storage[${storage.name}] was not found."
        }

    private fun eventStreamQueryServiceFactory(storage: StorageType): EventStreamQueryServiceFactory =
        eventStreamQueryServiceFactoryBindingsByStorage[storage]?.eventStreamQueryServiceFactory
            ?: NoOpEventStreamQueryServiceFactory

    private fun snapshotQueryServiceFactory(storage: StorageType): SnapshotQueryServiceFactory =
        snapshotQueryServiceFactoryBindingsByStorage[storage]?.snapshotQueryServiceFactory
            ?: NoOpSnapshotQueryServiceFactory

    companion object {
        private const val EVENT_CHANNEL = "event"
        private const val SNAPSHOT_CHANNEL = "snapshot"
    }
}

data class ResolvedEventRoutes(
    val defaultEventStore: EventStore,
    val eventRoutes: Map<NamedAggregate, EventStore>
)

data class ResolvedSnapshotRoutes(
    val defaultSnapshotStore: SnapshotStore,
    val snapshotRoutes: Map<NamedAggregate, SnapshotStore>
)

data class ResolvedEventStreamQueryServiceFactoryRoutes(
    val defaultEventStreamQueryServiceFactory: EventStreamQueryServiceFactory,
    val eventStreamQueryServiceFactoryRoutes: Map<NamedAggregate, EventStreamQueryServiceFactory>
)

data class ResolvedSnapshotQueryServiceFactoryRoutes(
    val defaultSnapshotQueryServiceFactory: SnapshotQueryServiceFactory,
    val snapshotQueryServiceFactoryRoutes: Map<NamedAggregate, SnapshotQueryServiceFactory>
)
