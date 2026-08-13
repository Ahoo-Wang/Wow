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
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.event.NoOpEventStreamQueryServiceFactory
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import java.util.Collections

internal sealed interface QueryBackendSelection {
    data class Available(val binding: QueryBackendBinding) : QueryBackendSelection

    data object Unavailable : QueryBackendSelection
}

internal sealed interface ResolvedStorageChannelRoute {
    val bindingName: String
    val storage: StorageType?
    val documentKind: QueryDocumentKind
    val queryBackendSelection: QueryBackendSelection

    data class Event(
        override val bindingName: String,
        override val storage: StorageType?,
        val eventStore: EventStore,
        val legacyQueryFactory: EventStreamQueryServiceFactory,
        override val queryBackendSelection: QueryBackendSelection,
    ) : ResolvedStorageChannelRoute {
        override val documentKind: QueryDocumentKind = QueryDocumentKind.EVENT_STREAM
    }

    data class Snapshot(
        override val bindingName: String,
        override val storage: StorageType?,
        val snapshotStore: SnapshotStore,
        val legacyQueryFactory: SnapshotQueryServiceFactory,
        override val queryBackendSelection: QueryBackendSelection,
    ) : ResolvedStorageChannelRoute {
        override val documentKind: QueryDocumentKind = QueryDocumentKind.SNAPSHOT
    }
}

internal class QueryBackendRouteSnapshot(
    defaultSelections: Map<QueryDocumentKind, QueryBackendSelection>,
    routeOverrides: Map<QueryTarget, QueryBackendSelection>,
) {
    val defaultSelections: Map<QueryDocumentKind, QueryBackendSelection> =
        defaultSelections.toImmutableLinkedMap()
    val routeOverrides: Map<QueryTarget, QueryBackendSelection> = routeOverrides.toImmutableLinkedMap()

    fun selection(target: QueryTarget): QueryBackendSelection =
        if (routeOverrides.containsKey(target)) {
            checkNotNull(routeOverrides[target])
        } else {
            defaultSelections[target.documentKind] ?: QueryBackendSelection.Unavailable
        }
}

internal class ResolvedStorageRouteSnapshot(
    val defaultEvent: ResolvedStorageChannelRoute.Event,
    val defaultSnapshot: ResolvedStorageChannelRoute.Snapshot?,
    routeOverrides: Map<QueryTarget, ResolvedStorageChannelRoute>,
) {
    val routeOverrides: Map<QueryTarget, ResolvedStorageChannelRoute> = routeOverrides.toImmutableLinkedMap()

    fun eventRoutes(): ResolvedEventRoutes = ResolvedEventRoutes(
        defaultEventStore = defaultEvent.eventStore,
        eventRoutes = routeOverrides.mapNotNullToLinkedMap { (target, route) ->
            (route as? ResolvedStorageChannelRoute.Event)?.let {
                target.namedAggregate.materialized() to it.eventStore
            }
        },
    )

    fun snapshotRoutes(): ResolvedSnapshotRoutes {
        val defaultRoute = requireNotNull(defaultSnapshot) { "Snapshot storage routing is disabled." }
        return ResolvedSnapshotRoutes(
            defaultSnapshotStore = defaultRoute.snapshotStore,
            snapshotRoutes = routeOverrides.mapNotNullToLinkedMap { (target, route) ->
                (route as? ResolvedStorageChannelRoute.Snapshot)?.let {
                    target.namedAggregate.materialized() to it.snapshotStore
                }
            },
        )
    }

    fun eventStreamQueryServiceFactoryRoutes(): ResolvedEventStreamQueryServiceFactoryRoutes =
        ResolvedEventStreamQueryServiceFactoryRoutes(
            defaultEventStreamQueryServiceFactory = defaultEvent.legacyQueryFactory,
            eventStreamQueryServiceFactoryRoutes = routeOverrides.mapNotNullToLinkedMap { (target, route) ->
                (route as? ResolvedStorageChannelRoute.Event)?.let {
                    target.namedAggregate.materialized() to it.legacyQueryFactory
                }
            },
        )

    fun snapshotQueryServiceFactoryRoutes(): ResolvedSnapshotQueryServiceFactoryRoutes {
        val defaultRoute = requireNotNull(defaultSnapshot) { "Snapshot storage routing is disabled." }
        return ResolvedSnapshotQueryServiceFactoryRoutes(
            defaultSnapshotQueryServiceFactory = defaultRoute.legacyQueryFactory,
            snapshotQueryServiceFactoryRoutes = routeOverrides.mapNotNullToLinkedMap { (target, route) ->
                (route as? ResolvedStorageChannelRoute.Snapshot)?.let {
                    target.namedAggregate.materialized() to it.legacyQueryFactory
                }
            },
        )
    }

    fun queryBackendRoutes(): QueryBackendRouteSnapshot {
        val defaults = linkedMapOf<QueryDocumentKind, QueryBackendSelection>(
            QueryDocumentKind.EVENT_STREAM to defaultEvent.queryBackendSelection,
        )
        defaultSnapshot?.let { defaults[QueryDocumentKind.SNAPSHOT] = it.queryBackendSelection }
        return QueryBackendRouteSnapshot(
            defaultSelections = defaults,
            routeOverrides = routeOverrides.mapValuesTo(LinkedHashMap()) { (_, route) ->
                route.queryBackendSelection
            },
        )
    }
}

internal class StorageRouteCoordinator(
    private val contextName: String,
    private val snapshotEnabled: Boolean,
    eventStoreBindings: List<EventStoreBinding>,
    snapshotStoreBindings: List<SnapshotStoreBinding>,
    eventStreamQueryServiceFactoryBindings: List<EventStreamQueryServiceFactoryBinding>,
    snapshotQueryServiceFactoryBindings: List<SnapshotQueryServiceFactoryBinding>,
    queryBackendBindings: List<QueryBackendBinding>,
    private val defaultEventStorage: StorageType,
    private val defaultSnapshotStorage: StorageType,
) {
    private val eventStores = BindingIndex(
        "event store",
        eventStoreBindings,
        EventStoreBinding::name,
        EventStoreBinding::storage,
    )
    private val snapshotStores = BindingIndex(
        "snapshot store",
        snapshotStoreBindings,
        SnapshotStoreBinding::name,
        SnapshotStoreBinding::storage,
    )
    private val eventQueryFactories = BindingIndex(
        "event query factory",
        eventStreamQueryServiceFactoryBindings,
        EventStreamQueryServiceFactoryBinding::name,
        EventStreamQueryServiceFactoryBinding::storage,
    )
    private val snapshotQueryFactories = BindingIndex(
        "snapshot query factory",
        snapshotQueryServiceFactoryBindings,
        SnapshotQueryServiceFactoryBinding::name,
        SnapshotQueryServiceFactoryBinding::storage,
    )
    private val backendBindings: List<QueryBackendBinding> = queryBackendBindings.toList()
    private val backendByName: Map<String, QueryBackendBinding>
    private val backendByStorageAndKind: Map<Pair<StorageType, QueryDocumentKind>, QueryBackendBinding>

    init {
        backendByName = uniqueIndex("query backend", backendBindings, QueryBackendBinding::name)
        backendByStorageAndKind = uniqueBackendStorageIndex(backendBindings)
    }

    fun resolve(properties: StorageRoutingProperties): ResolvedStorageRouteSnapshot {
        validateSnapshotRoutes(properties)
        val defaultEvent = resolveDefaultEvent()
        val defaultSnapshot = if (snapshotEnabled) resolveDefaultSnapshot() else null
        val overrides = LinkedHashMap<QueryTarget, ResolvedStorageChannelRoute>()
        properties.aggregates.forEach { (routeKey, aggregateRoute) ->
            val namedAggregate = resolveNamedAggregate(routeKey)
            aggregateRoute.event?.let { channel ->
                putUnique(
                    overrides,
                    QueryTarget(namedAggregate, QueryDocumentKind.EVENT_STREAM),
                    resolveEvent(routeKey, channel),
                )
            }
            aggregateRoute.snapshot?.let { channel ->
                putUnique(
                    overrides,
                    QueryTarget(namedAggregate, QueryDocumentKind.SNAPSHOT),
                    resolveSnapshot(routeKey, channel),
                )
            }
        }
        return ResolvedStorageRouteSnapshot(defaultEvent, defaultSnapshot, overrides)
    }

    private fun resolveDefaultEvent(): ResolvedStorageChannelRoute.Event {
        val store = eventStores.requiredByStorage(defaultEventStorage, "<default>", EVENT_CHANNEL)
        return eventRoute(store, backendSelection(store, QueryDocumentKind.EVENT_STREAM))
    }

    private fun resolveDefaultSnapshot(): ResolvedStorageChannelRoute.Snapshot {
        val store = snapshotStores.requiredByStorage(defaultSnapshotStorage, "<default>", SNAPSHOT_CHANNEL)
        return snapshotRoute(store, backendSelection(store, QueryDocumentKind.SNAPSHOT))
    }

    private fun resolveEvent(
        routeKey: String,
        channel: StorageChannelRouteProperties,
    ): ResolvedStorageChannelRoute.Event {
        validateChannel(routeKey, EVENT_CHANNEL, channel)
        val store = channel.storage?.let { eventStores.requiredByStorage(it, routeKey, EVENT_CHANNEL) }
            ?: eventStores.requiredByName(channel.binding.orEmpty().trim(), routeKey, EVENT_CHANNEL)
        val selection = if (channel.storage != null) {
            backendSelection(store, QueryDocumentKind.EVENT_STREAM)
        } else {
            requiredNamedBackend(store.name, store.storage, QueryDocumentKind.EVENT_STREAM, routeKey)
        }
        return eventRoute(store, selection)
    }

    private fun resolveSnapshot(
        routeKey: String,
        channel: StorageChannelRouteProperties,
    ): ResolvedStorageChannelRoute.Snapshot {
        validateChannel(routeKey, SNAPSHOT_CHANNEL, channel)
        val store = channel.storage?.let { snapshotStores.requiredByStorage(it, routeKey, SNAPSHOT_CHANNEL) }
            ?: snapshotStores.requiredByName(channel.binding.orEmpty().trim(), routeKey, SNAPSHOT_CHANNEL)
        val selection = if (channel.storage != null) {
            backendSelection(store, QueryDocumentKind.SNAPSHOT)
        } else {
            requiredNamedBackend(store.name, store.storage, QueryDocumentKind.SNAPSHOT, routeKey)
        }
        return snapshotRoute(store, selection)
    }

    private fun eventRoute(
        store: EventStoreBinding,
        selection: QueryBackendSelection,
    ): ResolvedStorageChannelRoute.Event = ResolvedStorageChannelRoute.Event(
        bindingName = store.name,
        storage = store.storage,
        eventStore = store.eventStore,
        legacyQueryFactory = store.storage?.let { eventQueryFactories.byStorage[it] }
            ?.eventStreamQueryServiceFactory
            ?: eventQueryFactories.byName[store.name]?.eventStreamQueryServiceFactory
            ?: NoOpEventStreamQueryServiceFactory,
        queryBackendSelection = selection,
    )

    private fun snapshotRoute(
        store: SnapshotStoreBinding,
        selection: QueryBackendSelection,
    ): ResolvedStorageChannelRoute.Snapshot = ResolvedStorageChannelRoute.Snapshot(
        bindingName = store.name,
        storage = store.storage,
        snapshotStore = store.snapshotStore,
        legacyQueryFactory = store.storage?.let { snapshotQueryFactories.byStorage[it] }
            ?.snapshotQueryServiceFactory
            ?: snapshotQueryFactories.byName[store.name]?.snapshotQueryServiceFactory
            ?: NoOpSnapshotQueryServiceFactory,
        queryBackendSelection = selection,
    )

    private fun backendSelection(
        store: Any,
        documentKind: QueryDocumentKind,
    ): QueryBackendSelection {
        val bindingName: String
        val storage: StorageType?
        when (store) {
            is EventStoreBinding -> {
                bindingName = store.name
                storage = store.storage
            }
            is SnapshotStoreBinding -> {
                bindingName = store.name
                storage = store.storage
            }
            else -> error("Unsupported storage binding.")
        }
        val backend = storage?.let { backendByStorageAndKind[it to documentKind] }
            ?: return QueryBackendSelection.Unavailable
        require(backend.name == bindingName) {
            "Storage binding[$bindingName] and query backend[${backend.name}] must share a logical name."
        }
        return QueryBackendSelection.Available(backend)
    }

    private fun requiredNamedBackend(
        bindingName: String,
        storage: StorageType?,
        documentKind: QueryDocumentKind,
        routeKey: String,
    ): QueryBackendSelection.Available {
        val backend = requireNotNull(backendByName[bindingName]) {
            "Storage route[$routeKey] binding[$bindingName] query backend was not found."
        }
        require(backend.documentKind == documentKind) {
            "Storage route[$routeKey] binding[$bindingName] requires backend kind[${documentKind.name}]."
        }
        require(backend.storage == storage) {
            "Storage route[$routeKey] binding[$bindingName] backend storage is inconsistent."
        }
        return QueryBackendSelection.Available(backend)
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
                "Storage route[$routeKey] must be either aggregate or context.aggregate.",
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

    private fun validateSnapshotRoutes(properties: StorageRoutingProperties) {
        if (snapshotEnabled) return
        properties.aggregates.entries.firstOrNull { (_, route) -> route.snapshot != null }?.let { (routeKey, _) ->
            check(snapshotEnabled) {
                "Storage route[$routeKey] channel[$SNAPSHOT_CHANNEL] can not be configured when snapshot is disabled."
            }
        }
    }

    private fun validateChannel(
        routeKey: String,
        channelName: String,
        channel: StorageChannelRouteProperties,
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

    private fun putUnique(
        routes: MutableMap<QueryTarget, ResolvedStorageChannelRoute>,
        target: QueryTarget,
        route: ResolvedStorageChannelRoute,
    ) {
        require(!routes.containsKey(target)) {
            "Storage route[${target.namedAggregate.contextName}.${target.namedAggregate.aggregateName}] " +
                "contains a duplicate target[${target.documentKind.name}]."
        }
        routes[target] = route
    }

    companion object {
        private const val EVENT_CHANNEL = "event"
        private const val SNAPSHOT_CHANNEL = "snapshot"
    }
}

private class BindingIndex<T>(
    label: String,
    bindings: List<T>,
    name: (T) -> String,
    storage: (T) -> StorageType?,
) {
    val byName: Map<String, T> = uniqueIndex(label, bindings.toList(), name)
    val byStorage: Map<StorageType, T> = uniqueStorageIndex(label, bindings, storage)

    fun requiredByName(name: String, routeKey: String, channelName: String): T = requireNotNull(byName[name]) {
        "Storage route[$routeKey] channel[$channelName] binding[$name] was not found."
    }

    fun requiredByStorage(storage: StorageType, routeKey: String, channelName: String): T =
        requireNotNull(byStorage[storage]) {
            "Storage route[$routeKey] channel[$channelName] storage[${storage.name}] was not found."
        }
}

private fun <T> uniqueIndex(label: String, bindings: List<T>, name: (T) -> String): Map<String, T> {
    val result = LinkedHashMap<String, T>()
    bindings.forEach { binding ->
        val logicalName = name(binding)
        require(logicalName.isNotBlank() && logicalName == logicalName.trim()) {
            "$label logical name is invalid."
        }
        require(result.putIfAbsent(logicalName, binding) == null) {
            "Duplicate $label logical name[$logicalName]."
        }
    }
    return result.toMap(LinkedHashMap())
}

private fun <T> uniqueStorageIndex(
    label: String,
    bindings: List<T>,
    storage: (T) -> StorageType?,
): Map<StorageType, T> {
    val result = LinkedHashMap<StorageType, T>()
    bindings.forEach { binding ->
        storage(binding)?.let { storageType ->
            require(result.putIfAbsent(storageType, binding) == null) {
                "Duplicate $label storage[${storageType.name}]."
            }
        }
    }
    return result.toMap(LinkedHashMap())
}

private fun uniqueBackendStorageIndex(
    bindings: List<QueryBackendBinding>,
): Map<Pair<StorageType, QueryDocumentKind>, QueryBackendBinding> {
    val result = LinkedHashMap<Pair<StorageType, QueryDocumentKind>, QueryBackendBinding>()
    bindings.forEach { binding ->
        binding.storage?.let { storage ->
            val key = storage to binding.documentKind
            require(result.putIfAbsent(key, binding) == null) {
                "Duplicate query backend storage[${storage.name}] kind[${binding.documentKind.name}]."
            }
        }
    }
    return result.toMap(LinkedHashMap())
}

private inline fun <K, V, RK, RV> Map<K, V>.mapNotNullToLinkedMap(
    transform: (Map.Entry<K, V>) -> Pair<RK, RV>?,
): Map<RK, RV> {
    val result = LinkedHashMap<RK, RV>()
    entries.forEach { entry -> transform(entry)?.let { (key, value) -> result[key] = value } }
    return Collections.unmodifiableMap(result)
}

private fun <K, V> Map<K, V>.toImmutableLinkedMap(): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(this))

private fun NamedAggregate.materialized(): MaterializedNamedAggregate =
    MaterializedNamedAggregate(contextName, aggregateName)
