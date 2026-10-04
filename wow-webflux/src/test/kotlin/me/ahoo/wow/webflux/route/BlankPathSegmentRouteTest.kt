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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.messaging.compensation.EventCompensateSupporter
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import me.ahoo.wow.openapi.CommonComponent
import me.ahoo.wow.openapi.RouterSpecs
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.query.QueryEntryPolicy
import me.ahoo.wow.query.event.EventStreamQueryGateway
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.webflux.exception.DefaultGlobalExceptionHandler
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.command.CommandHandlerFunctionFactory
import me.ahoo.wow.webflux.route.command.CommandTestFixtures
import me.ahoo.wow.webflux.route.command.DEFAULT_TIME_OUT
import me.ahoo.wow.webflux.route.event.CountEventStreamHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.EventCompensateHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.LoadEventStreamHandlerFunctionFactory
import me.ahoo.wow.webflux.route.policy.CommandWaitPolicy
import me.ahoo.wow.webflux.route.query.DefaultQueryRequestScope
import me.ahoo.wow.webflux.route.snapshot.CountSnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.LoadSnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.RegenerateSnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.state.LoadAggregateHandlerFunctionFactory
import me.ahoo.wow.webflux.route.state.LoadTimeBasedAggregateHandlerFunctionFactory
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.RouterFunctions
import java.net.URI

/**
 * A route that declares `{tenantId}`, `{ownerId}` or `{id}` states that value in its path, and a gateway may authorize
 * on that segment. `%20` matches the segment and decodes to a blank value; it is rejected with `400 IllegalArgument`
 * and the `Command-Tenant-Id` / `Command-Owner-Id` / `Command-Aggregate-Id` headers are never read in its place.
 *
 * Every route kind is served as the server serves it (generated catalog, materialized handlers, the global exception
 * handler); the URI is sent as written, so the server decodes `%20` itself. The stores and gateways are strict mocks:
 * reaching one, with the header's value in place of the blank segment, fails the request with a 500.
 */
class BlankPathSegmentRouteTest {
    private val snapshotGateway = mockk<SnapshotQueryGateway<Any>> {
        every { entryPolicy } returns QueryEntryPolicy.DEFAULT
    }
    private val eventGateway = mockk<EventStreamQueryGateway> {
        every { entryPolicy } returns QueryEntryPolicy.DEFAULT
    }
    private val stateAggregateRepository = mockk<StateAggregateRepository>()

    private val client: WebTestClient = run {
        val exceptionHandler = WebFluxRequestExceptionHandler()
        val factories = listOf(
            CommandHandlerFunctionFactory(
                commandGateway = mockk<CommandGateway>(),
                commandMessageExtractor = CommandTestFixtures.MOCK_COMMAND_MESSAGE_EXTRACTOR,
                exceptionHandler = exceptionHandler,
                commandWaitPolicy = CommandWaitPolicy(DEFAULT_TIME_OUT)
            ),
            LoadAggregateHandlerFunctionFactory(stateAggregateRepository, exceptionHandler),
            LoadTimeBasedAggregateHandlerFunctionFactory(stateAggregateRepository, exceptionHandler),
            LoadSnapshotHandlerFunctionFactory({ snapshotGateway }, DefaultQueryRequestScope, exceptionHandler),
            RegenerateSnapshotHandlerFunctionFactory(
                stateAggregateFactory = mockk<StateAggregateFactory>(),
                eventStore = mockk<EventStore>(),
                snapshotStore = mockk<SnapshotStore>(),
                exceptionHandler = exceptionHandler
            ),
            LoadEventStreamHandlerFunctionFactory({ eventGateway }, DefaultQueryRequestScope, exceptionHandler),
            EventCompensateHandlerFunctionFactory(mockk<EventCompensateSupporter>(), exceptionHandler),
            CountSnapshotHandlerFunctionFactory({ snapshotGateway }, DefaultQueryRequestScope, exceptionHandler),
            CountEventStreamHandlerFunctionFactory({ eventGateway }, DefaultQueryRequestScope, exceptionHandler),
        )
        val handlerKeys = factories.map { it.handlerKey }.toSet()
        val materializer = HttpRouteMaterializer(RouteHandlerFunctionRegistrar(factories))
        val builder = RouterFunctions.route()
        RouterSpecs(MaterializedNamedBoundedContext("example-service")).build().toRouteCatalog().routes
            .filter { it.handlerKey in handlerKeys }
            .forEach {
                val binding = materializer.materialize(it)
                builder.route(binding.predicate, binding.handlerFunction)
            }
        WebTestClient.bindToRouterFunction(builder.build())
            .handlerStrategies(HandlerStrategies.builder().exceptionHandler(DefaultGlobalExceptionHandler()).build())
            .build()
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        // commands
        "DELETE, /tenant/%20/owner/owner-a/sales-order/order-a, tenantId",
        "DELETE, /tenant/tenant-a/owner/%20/sales-order/order-a, ownerId",
        "DELETE, /tenant/tenant-a/owner/owner-a/sales-order/%20, id",
        "PUT, /owner/%20/cart/recover, ownerId",
        // queries
        "POST, /tenant/%20/sales-order/snapshot/count, tenantId",
        "POST, /owner/%20/sales-order/snapshot/count, ownerId",
        "POST, /tenant/%20/owner/owner-a/sales-order/event/count, tenantId",
        "POST, /tenant/tenant-a/owner/%20/sales-order/event/count, ownerId",
        // state load, time-based load; the owner check of an owner = AGGREGATE_ID aggregate
        "GET, /tenant/%20/owner/owner-a/sales-order/order-a/state, tenantId",
        "GET, /tenant/tenant-a/owner/owner-a/sales-order/%20/state, id",
        "GET, /owner/%20/cart/state, ownerId",
        "GET, /tenant/%20/owner/owner-a/sales-order/order-a/state/time/1, tenantId",
        "GET, /owner/%20/cart/state/time/1, ownerId",
        // snapshot load and regenerate
        "GET, /tenant/%20/owner/owner-a/sales-order/order-a/snapshot, tenantId",
        "GET, /tenant/tenant-a/owner/%20/sales-order/order-a/snapshot, ownerId",
        "GET, /tenant/tenant-a/owner/owner-a/sales-order/%20/snapshot, id",
        "GET, /owner/%20/cart/snapshot, ownerId",
        "PUT, /tenant/%20/sales-order/order-a/snapshot, tenantId",
        // event load and compensate
        "GET, /tenant/%20/sales-order/order-a/event/1/2, tenantId",
        "PUT, /tenant/%20/sales-order/order-a/1/compensate, tenantId",
    )
    fun `a blank path segment is rejected and the header is not read in its place`(
        method: String,
        path: String,
        variable: String
    ) {
        val result = client.method(HttpMethod.valueOf(method)).uri(URI.create(path))
            .header(CommandComponent.Header.TENANT_ID, VICTIM)
            .header(CommandComponent.Header.OWNER_ID, VICTIM)
            .header(CommandComponent.Header.AGGREGATE_ID, VICTIM)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"op":"MATCH_ALL"}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectHeader().valueEquals(CommonComponent.Header.ERROR_CODE, ErrorCodes.ILLEGAL_ARGUMENT)
            .expectBody(String::class.java)
            .returnResult()
        result.responseBody.assert().contains("Path variable [$variable] must not be blank.")
    }

    private companion object {
        const val VICTIM = "victim"
    }
}
