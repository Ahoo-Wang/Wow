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

package me.ahoo.wow.webflux.route.identity

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.AggregateId
import me.ahoo.wow.api.annotation.OwnerId
import me.ahoo.wow.api.annotation.TenantId
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.factory.SimpleCommandBuilderRewriterRegistry
import me.ahoo.wow.command.factory.SimpleCommandMessageFactory
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.example.domain.order.Order
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.messaging.compensation.EventCompensateSupporter
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import me.ahoo.wow.openapi.RouterSpecs
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.query.QueryEntryPolicy
import me.ahoo.wow.query.event.EventStreamQueryGateway
import me.ahoo.wow.query.queryScope
import me.ahoo.wow.query.querySelection
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.rest.CommandHeaders
import me.ahoo.wow.rest.WowHeaders
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.webflux.exception.DefaultGlobalExceptionHandler
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.HttpRouteMaterializer
import me.ahoo.wow.webflux.route.RouteHandlerFunctionRegistrar
import me.ahoo.wow.webflux.route.command.CommandHandlerFunctionFactory
import me.ahoo.wow.webflux.route.command.DEFAULT_TIME_OUT
import me.ahoo.wow.webflux.route.command.extractor.DefaultCommandBuilderExtractor
import me.ahoo.wow.webflux.route.command.extractor.DefaultCommandMessageExtractor
import me.ahoo.wow.webflux.route.event.EventCompensateHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.LoadEventStreamHandlerFunctionFactory
import me.ahoo.wow.webflux.route.policy.CommandWaitPolicy
import me.ahoo.wow.webflux.route.query.CountQueryHandlerFunctionFactory
import me.ahoo.wow.webflux.route.query.DefaultQueryRequestScope
import me.ahoo.wow.webflux.route.snapshot.LoadSnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.RegenerateSnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.state.LoadAggregateHandlerFunctionFactory
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.RouterFunctions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.net.URI

/**
 * V3 (since 9.3.0): when the route fixes the tenant or owner (static tenant, `{tenantId}`, `{ownerId}`, or `{id}` of
 * an aggregate owned by its ID), a command body or a request header with a different value is rejected with
 * `400 IllegalArgument`. The same value, or no value, is fine; without a fixed value the old precedence stands.
 */
class IdentityConflictTest {
    data class IdentityCommand(
        @AggregateId val id: String,
        @TenantId val tenantId: String? = null,
        @OwnerId val ownerId: String? = null,
    )

    private val extractor = DefaultCommandMessageExtractor(
        commandMessageFactory = SimpleCommandMessageFactory(NoOpValidator, SimpleCommandBuilderRewriterRegistry()),
        commandBuilderExtractor = DefaultCommandBuilderExtractor
    )
    private val orderRoute = Order::class.java.aggregateRouteMetadata()
    private val cartRoute = Cart::class.java.aggregateRouteMetadata()

    private fun extract(
        route: AggregateRouteMetadata<*>,
        body: Any,
        request: MockServerRequest,
    ) = Mono.defer {
        // As the command handler does: identity is resolved inside the request's pipeline.
        extractor.extract(aggregateRouteMetadata = route, commandBody = body, request = request)
    }

    private fun orderRequest(): MockServerRequest.Builder = MockServerRequest.builder()
        .pathVariable(MessageRecords.TENANT_ID, "tenant-a")
        .pathVariable(MessageRecords.OWNER_ID, "owner-a")
        .pathVariable(MessageRecords.ID, "order-a")

    @Test
    fun `a body tenant contradicting the path tenant is rejected`() {
        extract(orderRoute, IdentityCommand(id = "order-a", tenantId = "victim"), orderRequest().build())
            .test()
            .expectErrorSatisfies {
                it.assert().isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessage(
                        "Conflicting tenantId: the route fixes [tenant-a], but the command body gives [victim]."
                    )
            }
            .verify()
    }

    @Test
    fun `a body owner contradicting the path owner is rejected`() {
        extract(orderRoute, IdentityCommand(id = "order-a", ownerId = "victim"), orderRequest().build())
            .test()
            .expectErrorSatisfies {
                it.assert().isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessage("Conflicting ownerId: the route fixes [owner-a], but the command body gives [victim].")
            }
            .verify()
    }

    /**
     * On an aggregate owned by its ID the owner is derived from `{id}`; a blank body `@OwnerId` states no owner there,
     * as in 9.2, where the blank was replaced with the aggregate ID.
     */
    @ParameterizedTest
    @CsvSource("''", "' '")
    fun `a blank body owner states no owner when the owner derives from the id`(blank: String) {
        val request = MockServerRequest.builder().pathVariable(MessageRecords.ID, "cart-a").build()
        extract(cartRoute, IdentityCommand(id = "cart-a", ownerId = blank), request)
            .test()
            .consumeNextWith {
                it.aggregateId.id.assert().isEqualTo("cart-a")
                it.ownerId.assert().isEqualTo("cart-a")
            }
            .verifyComplete()
    }

    /** A blank body owner still contradicts an owner the path states with `{ownerId}`. */
    @Test
    fun `a blank body owner contradicting the path owner is rejected`() {
        extract(orderRoute, IdentityCommand(id = "order-a", ownerId = " "), orderRequest().build())
            .test()
            .expectErrorSatisfies {
                it.assert().isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessage("Conflicting ownerId: the route fixes [owner-a], but the command body gives [ ].")
            }
            .verify()
    }

    /** A blank body `@TenantId` is a value: it contradicts `{tenantId}` and the static tenant. */
    @Test
    fun `a blank body tenant contradicting the path or static tenant is rejected`() {
        extract(orderRoute, IdentityCommand(id = "order-a", tenantId = " "), orderRequest().build())
            .test()
            .expectErrorSatisfies {
                it.assert().isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessage("Conflicting tenantId: the route fixes [tenant-a], but the command body gives [ ].")
            }
            .verify()
        val cartRequest = MockServerRequest.builder().pathVariable(MessageRecords.ID, "cart-a").build()
        extract(cartRoute, IdentityCommand(id = "cart-a", tenantId = " "), cartRequest)
            .test()
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }

    @Test
    fun `a body agreeing with the path is fine`() {
        extract(
            orderRoute,
            IdentityCommand(id = "order-a", tenantId = "tenant-a", ownerId = "owner-a"),
            orderRequest().build()
        ).test()
            .consumeNextWith {
                it.aggregateId.tenantId.assert().isEqualTo("tenant-a")
                it.ownerId.assert().isEqualTo("owner-a")
            }
            .verifyComplete()
    }

    /** Without a route-fixed value the body still wins over the header, as before 9.3.0. */
    @Test
    fun `a body may differ from a header when nothing fixes the fact`() {
        val request = MockServerRequest.builder()
            .header(CommandHeaders.TENANT_ID, "header-tenant")
            .header(CommandHeaders.OWNER_ID, "header-owner")
            .build()
        extract(
            orderRoute,
            IdentityCommand(id = "order-a", tenantId = "body-tenant", ownerId = "body-owner"),
            request
        ).test()
            .consumeNextWith {
                it.aggregateId.tenantId.assert().isEqualTo("body-tenant")
                it.ownerId.assert().isEqualTo("body-owner")
            }
            .verifyComplete()
    }

    @Test
    fun `a body tenant contradicting the static tenant is rejected`() {
        val staticTenantId = cartRoute.aggregateMetadata.staticTenantId!!
        val request = MockServerRequest.builder().pathVariable(MessageRecords.OWNER_ID, "owner-a").build()
        extract(cartRoute, IdentityCommand(id = "owner-a", tenantId = "victim"), request)
            .test()
            .expectErrorSatisfies {
                it.assert().isInstanceOf(IllegalArgumentException::class.java).hasMessage(
                    "Conflicting tenantId: the route fixes [$staticTenantId], but the command body gives [victim]."
                )
            }
            .verify()
        extract(cartRoute, IdentityCommand(id = "owner-a", tenantId = staticTenantId), request)
            .test()
            .consumeNextWith { it.aggregateId.tenantId.assert().isEqualTo(staticTenantId) }
            .verifyComplete()
    }

    /**
     * A cart is owned by its ID. On a route that states `{id}` but not `{ownerId}`, the path's ID is the aggregate and
     * its owner; before 9.3.0 a `Command-Owner-Id` header replaced both.
     */
    @Test
    fun `on an aggregate owned by its ID the path id wins over the owner header`() {
        val withoutHeader = MockServerRequest.builder().pathVariable(MessageRecords.ID, "cart-a").build()
        extract(cartRoute, IdentityCommand(id = "cart-a"), withoutHeader)
            .test()
            .consumeNextWith {
                it.aggregateId.id.assert().isEqualTo("cart-a")
                it.ownerId.assert().isEqualTo("cart-a")
            }
            .verifyComplete()

        val contradicting = MockServerRequest.builder()
            .pathVariable(MessageRecords.ID, "cart-a")
            .header(CommandHeaders.OWNER_ID, "victim")
            .build()
        extract(cartRoute, IdentityCommand(id = "cart-a"), contradicting)
            .test()
            .expectErrorSatisfies {
                it.assert().isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessage("Conflicting ownerId: the route fixes [cart-a], but the request header gives [victim].")
            }
            .verify()
    }

    private val countGateway = mockk<SnapshotQueryGateway<Any>> {
        every { entryPolicy } returns QueryEntryPolicy.DEFAULT
        every { count(any()) } returns Mono.just(1L)
    }

    /**
     * One stored event of `cart-a` whose owner is blank, as for a cart created in-process (`CommandGateway` or a saga
     * do not apply owner = aggregate ID). The stub returns it unless the query filters by another owner.
     */
    private val countEventGateway = mockk<EventStreamQueryGateway> {
        every { entryPolicy } returns QueryEntryPolicy.DEFAULT
        every { count(any()) } returns Mono.just(1L)
        every { dynamicList(any()) } returns Flux.deferContextual { context ->
            val owners = (context.queryScope().leaves() + context.querySelection().leaves())
                .filterIsInstance<OwnerIdFilter>()
            if (owners.any { it.value != BLANK_OWNER }) {
                Flux.empty()
            } else {
                Flux.just(JsonSerializer.createObjectNode().put("aggregateId", "cart-a").put("ownerId", BLANK_OWNER))
            }
        }
    }

    private fun FilterExpression.leaves(): List<FilterExpression> =
        if (this is AndFilter) operands.flatMap { it.leaves() } else listOf(this)

    /**
     * A read of an aggregate owned by its ID, on a route that states `{id}` only, does not filter by the owner it
     * derives from `{id}`: a stream created in-process (blank owner) is still returned, as in 9.2.
     */
    @Test
    fun `a read does not filter by the owner derived from the id`() {
        client.get().uri("/cart/cart-a/event/1/10")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .value { it.assert().contains("\"aggregateId\":\"cart-a\"") }
    }

    private val client: WebTestClient = run {
        val exceptionHandler = WebFluxRequestExceptionHandler()
        val stateAggregateRepository = mockk<StateAggregateRepository>()
        val factories = listOf(
            CommandHandlerFunctionFactory(
                commandGateway = mockk<CommandGateway> {
                    every { sendAndWait(any<CommandMessage<Any>>(), any()) } returns Mono.never()
                },
                commandMessageExtractor = extractor,
                exceptionHandler = exceptionHandler,
                commandWaitPolicy = CommandWaitPolicy(DEFAULT_TIME_OUT)
            ),
            LoadAggregateHandlerFunctionFactory(stateAggregateRepository, exceptionHandler),
            LoadSnapshotHandlerFunctionFactory({ countGateway }, DefaultQueryRequestScope, exceptionHandler),
            RegenerateSnapshotHandlerFunctionFactory(
                stateAggregateFactory = mockk<StateAggregateFactory>(),
                eventStore = mockk<EventStore>(),
                snapshotStore = mockk<SnapshotStore>(),
                exceptionHandler = exceptionHandler
            ),
            LoadEventStreamHandlerFunctionFactory({ countEventGateway }, DefaultQueryRequestScope, exceptionHandler),
            EventCompensateHandlerFunctionFactory(mockk<EventCompensateSupporter>(), exceptionHandler),
            CountQueryHandlerFunctionFactory(
                BuiltInHttpRouteHandlerKeys.Snapshot.COUNT,
                { countGateway },
                DefaultQueryRequestScope,
                exceptionHandler
            ),
            CountQueryHandlerFunctionFactory(
                BuiltInHttpRouteHandlerKeys.Event.COUNT,
                { countEventGateway },
                DefaultQueryRequestScope,
                exceptionHandler
            ),
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

    @ParameterizedTest(name = "{0} {1} {2}")
    @CsvSource(
        // commands
        "DELETE, /tenant/tenant-a/owner/owner-a/sales-order/order-a, Command-Tenant-Id, tenantId, tenant-a",
        "DELETE, /tenant/tenant-a/owner/owner-a/sales-order/order-a, Command-Owner-Id, ownerId, owner-a",
        // queries
        "POST, /tenant/tenant-a/sales-order/snapshot/count, Command-Tenant-Id, tenantId, tenant-a",
        "POST, /owner/owner-a/sales-order/event/count, Command-Owner-Id, ownerId, owner-a",
        // point reads, regenerate, compensate
        "GET, /tenant/tenant-a/owner/owner-a/sales-order/order-a/state, Command-Tenant-Id, tenantId, tenant-a",
        "GET, /tenant/tenant-a/owner/owner-a/sales-order/order-a/snapshot, Command-Owner-Id, ownerId, owner-a",
        "GET, /tenant/tenant-a/sales-order/order-a/event/1/2, Command-Tenant-Id, tenantId, tenant-a",
        "PUT, /tenant/tenant-a/sales-order/order-a/snapshot, Command-Tenant-Id, tenantId, tenant-a",
        "PUT, /tenant/tenant-a/sales-order/order-a/1/compensate, Command-Tenant-Id, tenantId, tenant-a",
        // a cart's owner is its ID, which this path states
        "GET, /cart/cart-a/event/1/2, Command-Owner-Id, ownerId, cart-a",
    )
    fun `a header contradicting what the route fixes is rejected`(
        method: String,
        path: String,
        header: String,
        variable: String,
        fixed: String,
    ) {
        val result = client.method(HttpMethod.valueOf(method)).uri(URI.create(path))
            .header(header, VICTIM)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"op":"MATCH_ALL"}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectHeader().valueEquals(WowHeaders.ERROR_CODE, ErrorCodes.ILLEGAL_ARGUMENT)
            .expectBody(String::class.java)
            .returnResult()
        result.responseBody.assert()
            .contains("Conflicting $variable: the route fixes [$fixed], but the request header gives [$VICTIM].")
    }

    @Test
    fun `a tenant header is ignored on a static tenant route, as in 9_2`() {
        client.post().uri("/cart/snapshot/count")
            .header(CommandHeaders.TENANT_ID, VICTIM)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"op":"MATCH_ALL"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).isEqualTo("1")
    }

    @ParameterizedTest
    @CsvSource(
        "/tenant/tenant-a/sales-order/snapshot/count, Command-Tenant-Id, tenant-a",
        "/owner/owner-a/sales-order/event/count, Command-Owner-Id, owner-a",
    )
    fun `a header agreeing with the route is fine`(path: String, header: String, value: String) {
        client.post().uri(path)
            .header(header, value)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"op":"MATCH_ALL"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).isEqualTo("1")
    }

    private companion object {
        const val BLANK_OWNER = ""
        const val VICTIM = "victim"
    }
}
