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
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType

class StorageRouteResolver(
    private val contextName: String,
    private val snapshotEnabled: Boolean,
    eventStoreBindings: List<EventStoreBinding>,
    snapshotStoreBindings: List<SnapshotStoreBinding>,
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

    fun resolveEventRoutes(properties: StorageRoutingProperties): ResolvedEventRoutes {
        val routes: Map<NamedAggregate, EventStore> = properties.aggregates.mapNotNull { (routeKey, aggregateRoute) ->
            val channel = aggregateRoute.event ?: return@mapNotNull null
            val namedAggregate = resolveNamedAggregate(routeKey)
            namedAggregate to resolveEventStore(routeKey, channel)
        }.toMap()
        return ResolvedEventRoutes(
            defaultEventStore = requiredEventStore(defaultEventStorage, "<default>", EVENT_CHANNEL),
            eventRoutes = routes,
        )
    }

    fun resolveSnapshotRoutes(properties: StorageRoutingProperties): ResolvedSnapshotRoutes {
        if (!snapshotEnabled) {
            properties.aggregates.entries
                .firstOrNull { (_, aggregateRoute) -> aggregateRoute.snapshot != null }
                ?.let { (routeKey, _) ->
                    check(snapshotEnabled) {
                        "Storage route[$routeKey] channel[$SNAPSHOT_CHANNEL] can not be configured when snapshot is disabled."
                    }
                }
        }

        val routes: Map<NamedAggregate, SnapshotStore> = properties.aggregates.mapNotNull { (routeKey, aggregateRoute) ->
            val channel = aggregateRoute.snapshot ?: return@mapNotNull null
            val namedAggregate = resolveNamedAggregate(routeKey)
            namedAggregate to resolveSnapshotStore(routeKey, channel)
        }.toMap()
        return ResolvedSnapshotRoutes(
            defaultSnapshotStore = requiredSnapshotStore(defaultSnapshotStorage, "<default>", SNAPSHOT_CHANNEL),
            snapshotRoutes = routes,
        )
    }

    private fun resolveNamedAggregate(routeKey: String): MaterializedNamedAggregate {
        val segments = routeKey.split('.')
        val namedAggregate = when (segments.size) {
            1 -> {
                require(contextName.isNotBlank()) {
                    "Storage route[$routeKey] requires a non-blank current context name."
                }
                MaterializedNamedAggregate(contextName, segments[0])
            }

            2 -> MaterializedNamedAggregate(segments[0], segments[1])

            else -> throw IllegalArgumentException(
                "Storage route[$routeKey] must be either aggregate or context.aggregate."
            )
        }
        require(namedAggregate.contextName.isNotBlank() && namedAggregate.aggregateName.isNotBlank()) {
            "Storage route[$routeKey] must not contain blank context or aggregate name."
        }
        require(MetadataSearcher.namedAggregateType.containsKey(namedAggregate)) {
            "Storage route[$routeKey] references unknown aggregate[$namedAggregate]."
        }
        return namedAggregate
    }

    private fun resolveEventStore(routeKey: String, channel: StorageChannelRouteProperties): EventStore {
        validateChannel(routeKey, EVENT_CHANNEL, channel)
        channel.storage?.let { storage ->
            return requiredEventStore(storage, routeKey, EVENT_CHANNEL)
        }
        val binding = channel.binding!!.trim()
        return requireNotNull(eventStoreBindingsByName[binding]?.eventStore) {
            "Storage route[$routeKey] channel[$EVENT_CHANNEL] binding[$binding] was not found."
        }
    }

    private fun resolveSnapshotStore(routeKey: String, channel: StorageChannelRouteProperties): SnapshotStore {
        validateChannel(routeKey, SNAPSHOT_CHANNEL, channel)
        channel.storage?.let { storage ->
            return requiredSnapshotStore(storage, routeKey, SNAPSHOT_CHANNEL)
        }
        val binding = channel.binding!!.trim()
        return requireNotNull(snapshotStoreBindingsByName[binding]?.snapshotStore) {
            "Storage route[$routeKey] channel[$SNAPSHOT_CHANNEL] binding[$binding] was not found."
        }
    }

    private fun validateChannel(
        routeKey: String,
        channelName: String,
        channel: StorageChannelRouteProperties
    ) {
        val hasStorage = channel.storage != null
        val hasBinding = !channel.binding.isNullOrBlank()
        require(hasStorage || hasBinding) {
            "Storage route[$routeKey] channel[$channelName] must configure either storage or binding."
        }
        require(!(hasStorage && hasBinding)) {
            "Storage route[$routeKey] channel[$channelName] can configure either storage or binding, not both."
        }
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
