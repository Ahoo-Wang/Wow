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

package me.ahoo.wow.webflux.route

import me.ahoo.test.asserts.assert
import me.ahoo.wow.eventsourcing.EventSourcingStateAggregateRepository
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.NoOpSnapshotRepository
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.openapi.aggregate.state.LoadAggregateRouteSpec
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.exception.DefaultRequestExceptionHandler
import me.ahoo.wow.webflux.route.state.LoadAggregateHandlerFunctionFactory
import org.junit.jupiter.api.Test

class RouteHandlerFunctionRegistrarTest {

    @Test
    fun `should register factory and retrieve by spec type`() {
        val registrar = RouteHandlerFunctionRegistrar()
        val stateRepository = EventSourcingStateAggregateRepository(
            stateAggregateFactory = ConstructorStateAggregateFactory,
            snapshotRepository = NoOpSnapshotRepository,
            eventStore = InMemoryEventStore(),
        )
        val factory = LoadAggregateHandlerFunctionFactory(
            stateAggregateRepository = stateRepository,
            exceptionHandler = DefaultRequestExceptionHandler,
        )
        registrar.register(factory)

        val loadAggregateSpec = LoadAggregateRouteSpec(
            MOCK_AGGREGATE_METADATA,
            aggregateRouteMetadata = MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata(),
            componentContext = OpenAPIComponentContext.default()
        )

        val found = registrar.getFactory(loadAggregateSpec)
        found.assert().isNotNull()
        found.assert().isSameAs(factory)
        @Suppress("UNCHECKED_CAST")
        val handlerFunction = (found as RouteHandlerFunctionFactory<LoadAggregateRouteSpec>).create(loadAggregateSpec)
        handlerFunction.assert().isNotNull()
    }

    @Test
    fun `should return null for unregistered spec type`() {
        val registrar = RouteHandlerFunctionRegistrar()
        val loadAggregateSpec = LoadAggregateRouteSpec(
            MOCK_AGGREGATE_METADATA,
            aggregateRouteMetadata = MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata(),
            componentContext = OpenAPIComponentContext.default()
        )
        registrar.getFactory(loadAggregateSpec).assert().isNull()
    }

    @Test
    fun `should overwrite factory when registering same spec type`() {
        val registrar = RouteHandlerFunctionRegistrar()
        val stateRepository = EventSourcingStateAggregateRepository(
            stateAggregateFactory = ConstructorStateAggregateFactory,
            snapshotRepository = NoOpSnapshotRepository,
            eventStore = InMemoryEventStore(),
        )
        val factory1 = LoadAggregateHandlerFunctionFactory(
            stateAggregateRepository = stateRepository,
            exceptionHandler = DefaultRequestExceptionHandler,
        )
        val factory2 = LoadAggregateHandlerFunctionFactory(
            stateAggregateRepository = stateRepository,
            exceptionHandler = DefaultRequestExceptionHandler,
        )
        registrar.register(factory1)
        registrar.register(factory2)

        val loadAggregateSpec = LoadAggregateRouteSpec(
            MOCK_AGGREGATE_METADATA,
            aggregateRouteMetadata = MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata(),
            componentContext = OpenAPIComponentContext.default()
        )

        val found = registrar.getFactory(loadAggregateSpec)
        found.assert().isSameAs(factory2)
    }
}

class RouterFunctionBuilderTest {

    @Test
    fun `should build router function with manually provided specs`() {
        val registrar = RouteHandlerFunctionRegistrar()
        val stateRepository = EventSourcingStateAggregateRepository(
            stateAggregateFactory = ConstructorStateAggregateFactory,
            snapshotRepository = NoOpSnapshotRepository,
            eventStore = InMemoryEventStore(),
        )
        registrar.register(
            LoadAggregateHandlerFunctionFactory(
                stateAggregateRepository = stateRepository,
                exceptionHandler = DefaultRequestExceptionHandler,
            )
        )

        val aggregateRouteMetadata = MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata()
        val loadAggregateSpec = LoadAggregateRouteSpec(
            MOCK_AGGREGATE_METADATA,
            aggregateRouteMetadata = aggregateRouteMetadata,
            componentContext = OpenAPIComponentContext.default()
        )

        // Use RouterSpecs with manual route addition via iterable constructor
        val routerSpecs = me.ahoo.wow.openapi.RouterSpecs(
            MOCK_AGGREGATE_METADATA,
            routes = mutableListOf(loadAggregateSpec),
        )
        val builder = RouterFunctionBuilder(routerSpecs, registrar)
        val routerFunction = builder.build()
        routerFunction.assert().isNotNull()
    }
}
