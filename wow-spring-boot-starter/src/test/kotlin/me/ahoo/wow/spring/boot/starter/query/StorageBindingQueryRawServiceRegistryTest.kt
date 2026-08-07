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

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.event.NoOpEventStreamQueryService
import me.ahoo.wow.query.gateway.GatewaySnapshotQueryServiceFactory
import me.ahoo.wow.query.gateway.QueryCallResolver
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryElementPathMode
import me.ahoo.wow.query.gateway.QueryGateway
import me.ahoo.wow.query.gateway.QueryMatchScopeMode
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryService
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.AggregateStorageRouteProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.EventStreamQueryServiceFactoryBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.SnapshotQueryServiceFactoryBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.StorageChannelRouteProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.StorageRoutingProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono

class StorageBindingQueryRawServiceRegistryTest {
    @Test
    fun `exact target should resolve raw storage and matching dialect`() {
        val mongoSnapshot = RecordingSnapshotFactory()
        val elasticsearchSnapshot = RecordingSnapshotFactory()
        val mongoEvent = RecordingEventFactory()
        val elasticsearchEvent = RecordingEventFactory()
        val registry = registry(
            properties = StorageRoutingProperties(
                aggregates = mapOf(
                    "order" to AggregateStorageRouteProperties(
                        event = StorageChannelRouteProperties(storage = StorageType.ELASTICSEARCH),
                        snapshot = StorageChannelRouteProperties(storage = StorageType.ELASTICSEARCH),
                    ),
                ),
            ),
            eventBindings = listOf(
                EventStreamQueryServiceFactoryBinding.storage(StorageType.MONGO, mongoEvent),
                EventStreamQueryServiceFactoryBinding.storage(StorageType.ELASTICSEARCH, elasticsearchEvent),
            ),
            snapshotBindings = listOf(
                SnapshotQueryServiceFactoryBinding.storage(StorageType.MONGO, mongoSnapshot),
                SnapshotQueryServiceFactoryBinding.storage(StorageType.ELASTICSEARCH, elasticsearchSnapshot),
            ),
        )

        registry.snapshot(ORDER)
        elasticsearchSnapshot.lastTarget.assert().isEqualTo(ORDER)
        registry.eventStream(ORDER)
        elasticsearchEvent.lastTarget.assert().isEqualTo(ORDER)

        registry.snapshot(CART)
        mongoSnapshot.lastTarget.assert().isEqualTo(CART)
        registry.eventStream(CART)
        mongoEvent.lastTarget.assert().isEqualTo(CART)

        val snapshotDialect = registry.resolveDialect(QueryTarget(ORDER, QueryDocumentKind.SNAPSHOT))
        snapshotDialect.elementPathMode.assert().isEqualTo(QueryElementPathMode.ROOT_QUALIFIED)
        snapshotDialect.matchScopeMode.assert().isEqualTo(QueryMatchScopeMode.FIELD)
        val eventDialect = registry.resolveDialect(QueryTarget(CART, QueryDocumentKind.EVENT_STREAM))
        eventDialect.elementPathMode.assert().isEqualTo(QueryElementPathMode.CURRENT_ELEMENT_RELATIVE)
        eventDialect.matchScopeMode.assert().isEqualTo(QueryMatchScopeMode.DOCUMENT)
    }

    @Test
    fun `gateway facade can not be registered as raw storage`() {
        val facade = GatewaySnapshotQueryServiceFactory(
            mockk<QueryGateway>(),
            QueryCallResolver { Mono.empty() },
        )

        val exception = assertThrows<IllegalArgumentException> {
            registry(
                snapshotBindings = listOf(
                    SnapshotQueryServiceFactoryBinding.storage(StorageType.MONGO, facade),
                ),
            )
        }

        exception.message.assert().contains("cannot be registered as a raw snapshot query binding")
    }

    private fun registry(
        properties: StorageRoutingProperties = StorageRoutingProperties(),
        eventBindings: List<EventStreamQueryServiceFactoryBinding> = emptyList(),
        snapshotBindings: List<SnapshotQueryServiceFactoryBinding> = emptyList(),
    ): StorageBindingQueryRawServiceRegistry = StorageBindingQueryRawServiceRegistry(
        contextName = "order-service",
        storageRoutingProperties = properties,
        eventStreamBindings = eventBindings,
        snapshotBindings = snapshotBindings,
        snapshotEnabled = true,
        defaultEventStorage = StorageType.MONGO,
        defaultSnapshotStorage = StorageType.MONGO,
    )

    private class RecordingSnapshotFactory : SnapshotQueryServiceFactory {
        var lastTarget: NamedAggregate? = null

        override fun <S : Any> create(namedAggregate: NamedAggregate): SnapshotQueryService<S> {
            lastTarget = namedAggregate
            return NoOpSnapshotQueryService(namedAggregate)
        }
    }

    private class RecordingEventFactory : EventStreamQueryServiceFactory {
        var lastTarget: NamedAggregate? = null

        override fun create(namedAggregate: NamedAggregate): EventStreamQueryService {
            lastTarget = namedAggregate
            return NoOpEventStreamQueryService(namedAggregate)
        }
    }

    private companion object {
        val ORDER = MaterializedNamedAggregate("order-service", "order")
        val CART = MaterializedNamedAggregate("order-service", "cart")
    }
}
