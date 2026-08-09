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

@file:OptIn(me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class)

package me.ahoo.wow.spring.boot.starter.query

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.event.NoOpEventStreamQueryServiceFactory
import me.ahoo.wow.query.gateway.GatewayEventStreamQueryServiceFactory
import me.ahoo.wow.query.gateway.GatewaySnapshotQueryServiceFactory
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryElementPathMode
import me.ahoo.wow.query.gateway.QueryLegacyDialect
import me.ahoo.wow.query.gateway.QueryMatchScopeMode
import me.ahoo.wow.query.gateway.QueryRawServiceSource
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.EventStreamQueryServiceFactoryBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.SnapshotQueryServiceFactoryBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.StorageRouteResolver
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.StorageRoutingProperties

/** Raw query route owner. It is deliberately a different type from the public application facade factories. */
internal class StorageBindingQueryRawServiceRegistry(
    contextName: String,
    storageRoutingProperties: StorageRoutingProperties,
    eventStreamBindings: List<EventStreamQueryServiceFactoryBinding>,
    snapshotBindings: List<SnapshotQueryServiceFactoryBinding>,
    snapshotEnabled: Boolean,
    defaultEventStorage: StorageType,
    defaultSnapshotStorage: StorageType,
) : QueryRawServiceSource {
    private val eventStreamBindings = eventStreamBindings.toList().also(::rejectFacadeEventBindings)
    private val snapshotBindings = snapshotBindings.toList().also(::rejectFacadeSnapshotBindings)
    private val eventRoutes: RawEventRoutes
    private val snapshotRoutes: RawSnapshotRoutes

    init {
        val resolver = StorageRouteResolver(
            contextName = contextName,
            snapshotEnabled = snapshotEnabled,
            eventStoreBindings = emptyList(),
            snapshotStoreBindings = emptyList(),
            eventStreamQueryServiceFactoryBindings = this.eventStreamBindings,
            snapshotQueryServiceFactoryBindings = this.snapshotBindings,
            defaultEventStorage = defaultEventStorage,
            defaultSnapshotStorage = defaultSnapshotStorage,
        )
        resolver.resolveEventStreamQueryServiceFactoryRoutes(storageRoutingProperties).let { resolved ->
            eventRoutes = RawEventRoutes(
                resolved.defaultEventStreamQueryServiceFactory,
                resolved.eventStreamQueryServiceFactoryRoutes.mapKeys { (aggregate, _) -> aggregate.materialize() },
            )
        }
        resolver.resolveSnapshotQueryServiceFactoryRoutes(storageRoutingProperties).let { resolved ->
            snapshotRoutes = RawSnapshotRoutes(
                resolved.defaultSnapshotQueryServiceFactory,
                resolved.snapshotQueryServiceFactoryRoutes.mapKeys { (aggregate, _) -> aggregate.materialize() },
            )
        }
    }

    override fun snapshot(namedAggregate: NamedAggregate): SnapshotQueryService<*> =
        snapshotFactory(namedAggregate).create<Any>(namedAggregate.materialize())

    override fun eventStream(namedAggregate: NamedAggregate): EventStreamQueryService =
        eventStreamFactory(namedAggregate).create(namedAggregate.materialize())

    fun resolveDialect(target: QueryTarget): QueryLegacyDialect {
        val storage = resolveStorage(target)
        return when (storage) {
            StorageType.ELASTICSEARCH -> ELASTICSEARCH_DIALECT
            StorageType.MONGO -> MONGO_DIALECT
            null -> NO_OP_DIALECT
            else -> throw IllegalStateException(
                "Raw query route for target[$target] does not declare a supported legacy dialect.",
            )
        }
    }

    fun resolveStorage(target: QueryTarget): StorageType? = when (target.documentKind) {
        QueryDocumentKind.SNAPSHOT -> storageOf(snapshotFactory(target.namedAggregate), snapshotBindings)
        QueryDocumentKind.EVENT_STREAM -> storageOf(eventStreamFactory(target.namedAggregate), eventStreamBindings)
    }

    private fun snapshotFactory(namedAggregate: NamedAggregate): SnapshotQueryServiceFactory =
        snapshotRoutes.routes[namedAggregate.materialize()] ?: snapshotRoutes.defaultFactory

    private fun eventStreamFactory(namedAggregate: NamedAggregate): EventStreamQueryServiceFactory =
        eventRoutes.routes[namedAggregate.materialize()] ?: eventRoutes.defaultFactory

    private fun storageOf(
        factory: SnapshotQueryServiceFactory,
        bindings: List<SnapshotQueryServiceFactoryBinding>,
    ): StorageType? {
        if (factory === NoOpSnapshotQueryServiceFactory) {
            return null
        }
        return bindings.asSequence()
            .filter { binding -> binding.snapshotQueryServiceFactory === factory }
            .mapNotNull(SnapshotQueryServiceFactoryBinding::storage)
            .distinct()
            .singleOrNull()
            ?: throw IllegalStateException(
                "Raw snapshot query factory[${factory.javaClass.name}] requires an explicit QueryLegacyDialectResolver.",
            )
    }

    private fun storageOf(
        factory: EventStreamQueryServiceFactory,
        bindings: List<EventStreamQueryServiceFactoryBinding>,
    ): StorageType? {
        if (factory === NoOpEventStreamQueryServiceFactory) {
            return null
        }
        return bindings.asSequence()
            .filter { binding -> binding.eventStreamQueryServiceFactory === factory }
            .mapNotNull(EventStreamQueryServiceFactoryBinding::storage)
            .distinct()
            .singleOrNull()
            ?: throw IllegalStateException(
                "Raw event-stream query factory[${factory.javaClass.name}] requires an explicit " +
                    "QueryLegacyDialectResolver.",
            )
    }

    private data class RawSnapshotRoutes(
        val defaultFactory: SnapshotQueryServiceFactory,
        val routes: Map<MaterializedNamedAggregate, SnapshotQueryServiceFactory>,
    )

    private data class RawEventRoutes(
        val defaultFactory: EventStreamQueryServiceFactory,
        val routes: Map<MaterializedNamedAggregate, EventStreamQueryServiceFactory>,
    )

    private companion object {
        val MONGO_DIALECT = QueryLegacyDialect(
            QueryElementPathMode.CURRENT_ELEMENT_RELATIVE,
            QueryMatchScopeMode.DOCUMENT,
        )
        val ELASTICSEARCH_DIALECT = QueryLegacyDialect(
            QueryElementPathMode.ROOT_QUALIFIED,
            QueryMatchScopeMode.FIELD,
        )
        val NO_OP_DIALECT = MONGO_DIALECT

        fun rejectFacadeSnapshotBindings(bindings: List<SnapshotQueryServiceFactoryBinding>) {
            bindings.forEach { binding ->
                require(binding.snapshotQueryServiceFactory !is GatewaySnapshotQueryServiceFactory) {
                    "Gateway facade factory[${binding.name}] cannot be registered as a raw snapshot query binding."
                }
            }
        }

        fun rejectFacadeEventBindings(bindings: List<EventStreamQueryServiceFactoryBinding>) {
            bindings.forEach { binding ->
                require(binding.eventStreamQueryServiceFactory !is GatewayEventStreamQueryServiceFactory) {
                    "Gateway facade factory[${binding.name}] cannot be registered as a raw event-stream query binding."
                }
            }
        }
    }
}
