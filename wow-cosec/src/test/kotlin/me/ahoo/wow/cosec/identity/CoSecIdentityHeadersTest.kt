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

package me.ahoo.wow.cosec.identity

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.factory.CommandBuilder
import me.ahoo.wow.example.domain.order.Order
import me.ahoo.wow.example.domain.order.OrderState
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.openapi.CommonComponent
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.webflux.route.command.extractor.CommandBuilderExtractor
import me.ahoo.wow.webflux.route.command.extractor.DefaultCommandBuilderExtractor
import me.ahoo.wow.webflux.route.identity.RouteIdentity
import me.ahoo.wow.webflux.route.query.DefaultQueryRequestScope
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.web.reactive.function.server.ServerRequest

/**
 * CoSec's headers as identity header aliases: the default command builder extractor and query request scope, reading
 * the aliases the route carries, take `CoSec-Space-Id` / `CoSec-Request-Id` where Wow's own header is absent, as if
 * the request had sent Wow's header.
 */
class CoSecIdentityHeadersTest {
    private val spacedRoute = Order::class.java.aggregateRouteMetadata()
    private val plainRoute = MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata()

    /**
     * A request as the router hands it over: with [aliases], the route identity the router materialized with CoSec's
     * aliases; without, a route with no aliases.
     */
    private fun request(
        wowSpace: String?,
        coSecSpace: String?,
        wowRequest: String?,
        coSecRequest: String?,
        aliases: Boolean = true,
    ): ServerRequest =
        MockServerRequest.builder()
            .pathVariable(MessageRecords.TENANT_ID, "tenant-a")
            .pathVariable(MessageRecords.OWNER_ID, "owner-a")
            .apply {
                if (aliases) {
                    attribute(
                        RouteIdentity.ATTRIBUTE,
                        RouteIdentity(
                            pathVariables = setOf(MessageRecords.TENANT_ID, MessageRecords.OWNER_ID),
                            aliases = CoSecIdentityHeaders.ALIASES
                        )
                    )
                }
            }
            .apply {
                wowSpace?.let { header(CommonComponent.Header.SPACE_ID, it) }
                coSecSpace?.let { header(CoSecIdentityHeaders.SPACE_ID, it) }
                wowRequest?.let { header(CommandComponent.Header.REQUEST_ID, it) }
                coSecRequest?.let { header(CoSecIdentityHeaders.REQUEST_ID, it) }
            }
            .build()

    private fun CommandBuilder.identity() = listOf(tenantId, ownerId, aggregateId, spaceId, requestId)

    private fun build(
        extractor: CommandBuilderExtractor,
        route: AggregateRouteMetadata<*>,
        request: ServerRequest,
    ) = extractor.extract(route, MockCreateAggregate(id = "id-a", data = "data"), request).block()!!.identity()

    @ParameterizedTest
    @CsvSource(
        "wow-space, cosec-space, wow-request, cosec-request",
        ", cosec-space, , cosec-request",
        "wow-space, , wow-request, ",
        ", , , ",
    )
    fun `a CoSec header counts as Wow's own header where that is absent`(
        wowSpace: String?,
        coSecSpace: String?,
        wowRequest: String?,
        coSecRequest: String?,
    ) {
        val withAliases = request(wowSpace, coSecSpace, wowRequest, coSecRequest)
        val wowHeadersOnly = request(wowSpace ?: coSecSpace, null, wowRequest ?: coSecRequest, null, aliases = false)
        for (route in listOf(spacedRoute, plainRoute)) {
            build(DefaultCommandBuilderExtractor, route, withAliases).assert()
                .isEqualTo(build(DefaultCommandBuilderExtractor, route, wowHeadersOnly))
        }
        for (aggregate in listOf(aggregateMetadata<Order, OrderState>(), MOCK_AGGREGATE_METADATA)) {
            DefaultQueryRequestScope.resolve(aggregate, withAliases).assert()
                .isEqualTo(DefaultQueryRequestScope.resolve(aggregate, wowHeadersOnly))
        }
    }

    @Test
    fun `a CoSec space applies to a spaced aggregate only`() {
        val request = request(null, "cosec-space", null, null)
        build(DefaultCommandBuilderExtractor, spacedRoute, request)[3].assert().isEqualTo("cosec-space")
        build(DefaultCommandBuilderExtractor, plainRoute, request)[3].assert().isNull()
    }

    /** A blank alias header is no value, as a blank `Wow-Space-Id` / `Command-Request-Id` always was. */
    @Test
    fun `a blank CoSec header is no value`() {
        val request = request(null, " ", null, " ")
        build(DefaultCommandBuilderExtractor, spacedRoute, request).let {
            it[3].assert().isNull()
            it[4].assert().isNull()
        }
    }
}
