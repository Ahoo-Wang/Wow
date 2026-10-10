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
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.openapi.contract.HttpParameter
import me.ahoo.wow.openapi.contract.HttpParameterLocation
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.query.QueryEntryPolicy
import me.ahoo.wow.query.queryScope
import me.ahoo.wow.query.querySelection
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.rest.CommandHeaders
import me.ahoo.wow.rest.WowHeaders
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.webflux.exception.DefaultGlobalExceptionHandler
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.HttpRouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.HttpRouteMaterializer
import me.ahoo.wow.webflux.route.RouteHandlerFunctionRegistrar
import me.ahoo.wow.webflux.route.query.DefaultQueryRequestScope
import me.ahoo.wow.webflux.route.snapshot.LoadSnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.state.LoadAggregateHandlerFunctionFactory
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.RouterFunctions
import reactor.core.publisher.Mono

/**
 * Reads of an aggregate owned by its ID (`cart`) on custom routes that state `{id}` but not `{ownerId}`. The aggregate
 * was created in-process, so its stored owner is blank. The owner derived from `{id}` decides commands only; reads
 * behave as in 9.2: no owner filter is added, and the owner check of an owned aggregate reads the owner header.
 */
class AggregateIdOwnerReadTest {
    private val cartRoute = Cart::class.java.aggregateRouteMetadata()

    private val snapshotGateway = mockk<SnapshotQueryGateway<Any>> {
        every { entryPolicy } returns QueryEntryPolicy.DEFAULT
        every { dynamicSingle(any()) } returns Mono.deferContextual { context ->
            val owners = (context.queryScope().leaves() + context.querySelection().leaves())
                .filterIsInstance<OwnerIdFilter>()
            if (owners.any { it.value != BLANK_OWNER }) {
                Mono.empty()
            } else {
                Mono.just(JsonSerializer.createObjectNode().put("aggregateId", "cart-a").put("ownerId", BLANK_OWNER))
            }
        }
    }

    private val stateAggregateRepository = mockk<StateAggregateRepository> {
        every { load<Any>(any<AggregateId>(), any(), any<Int>()) } answers {
            Mono.just(
                mockk<StateAggregate<Any>> {
                    every { initialized } returns true
                    every { deleted } returns false
                    every { ownerId } returns BLANK_OWNER
                    every { aggregateId } returns firstArg()
                }
            )
        }
    }

    private fun FilterExpression.leaves(): List<FilterExpression> =
        if (this is AndFilter) operands.flatMap { it.leaves() } else listOf(this)

    private fun idRoute(path: String, factory: HttpRouteHandlerFunctionFactory) = HttpRouteContract(
        routeId = "test.$path",
        method = "GET",
        path = path,
        handlerKey = factory.handlerKey,
        parameters = listOf(HttpParameter(MessageRecords.ID, HttpParameterLocation.PATH, required = true)),
        handlerMetadata = HttpRouteHandlerMetadata.Aggregate(cartRoute),
    )

    private val client: WebTestClient = run {
        val exceptionHandler = WebFluxRequestExceptionHandler()
        val snapshot = LoadSnapshotHandlerFunctionFactory(
            { snapshotGateway },
            DefaultQueryRequestScope,
            exceptionHandler
        )
        val state = LoadAggregateHandlerFunctionFactory(stateAggregateRepository, exceptionHandler)
        val materializer = HttpRouteMaterializer(RouteHandlerFunctionRegistrar(listOf(snapshot, state)))
        val builder = RouterFunctions.route()
        listOf(idRoute("/cart/{id}/snapshot", snapshot), idRoute("/cart/{id}/state", state)).forEach {
            val binding = materializer.materialize(it)
            builder.route(binding.predicate, binding.handlerFunction)
        }
        WebTestClient.bindToRouterFunction(builder.build())
            .handlerStrategies(HandlerStrategies.builder().exceptionHandler(DefaultGlobalExceptionHandler()).build())
            .build()
    }

    @Test
    fun `a snapshot load does not filter by the owner derived from the id`() {
        client.get().uri("/cart/cart-a/snapshot")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .value { it.assert().contains("\"aggregateId\":\"cart-a\"") }
    }

    /**
     * As in 9.2, the owner check of an owned aggregate reads the request's owner (`Command-Owner-Id` here), not the
     * one derived from `{id}`: without it the request is a `400`, not an owner mismatch.
     */
    @Test
    fun `the state owner check reads the request owner, not the one derived from the id`() {
        client.get().uri("/cart/cart-a/state")
            .exchange()
            .expectStatus().isBadRequest
            .expectHeader().valueEquals(WowHeaders.ERROR_CODE, ErrorCodes.ILLEGAL_ARGUMENT)
    }

    @Test
    fun `a contradicting owner header is still rejected on a read`() {
        client.get().uri("/cart/cart-a/snapshot")
            .header(CommandHeaders.OWNER_ID, "victim")
            .exchange()
            .expectStatus().isBadRequest
            .expectHeader().valueEquals(WowHeaders.ERROR_CODE, ErrorCodes.ILLEGAL_ARGUMENT)
    }

    private companion object {
        const val BLANK_OWNER = ""
    }
}
