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
import me.ahoo.wow.openapi.contract.HttpParameter
import me.ahoo.wow.openapi.contract.HttpParameterLocation
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.webflux.route.identity.IdentityHeaderAliases
import me.ahoo.wow.webflux.route.identity.RouteIdentity
import org.junit.jupiter.api.Test
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse

class HttpRouteMaterializerTest {

    @Test
    fun `should materialize predicate and handler from contract`() {
        val contract = routeContract()
        val factory = CapturingHttpRouteHandlerFunctionFactory("handler.key")
        val materializer = HttpRouteMaterializer(
            routeHandlerFunctionRegistrar = RouteHandlerFunctionRegistrar(listOf(factory))
        )

        val binding = materializer.materialize(contract)

        binding.predicate.assert().isNotNull()
        factory.createdContract.assert().isSameAs(contract)
        factory.createdMetadata.assert().isSameAs(contract.handlerMetadata)
        val request = MockServerRequest.builder().build()
        binding.handlerFunction.handle(request).block()
        factory.handled.assert().isSameAs(request)
    }

    @Test
    fun `the route's identity binding is computed once and carried to its handler`() {
        val contract = routeContract().copy(
            path = "/tenant/{tenantId}/test/{id}",
            parameters = listOf(
                HttpParameter(MessageRecords.TENANT_ID, HttpParameterLocation.PATH, required = true),
                HttpParameter(MessageRecords.ID, HttpParameterLocation.PATH, required = true),
                HttpParameter("other", HttpParameterLocation.HEADER),
            ),
        )
        val factory = CapturingHttpRouteHandlerFunctionFactory("handler.key")
        val aliases = IdentityHeaderAliases(spaceId = listOf("X-Space"))
        val binding = HttpRouteMaterializer(
            routeHandlerFunctionRegistrar = RouteHandlerFunctionRegistrar(listOf(factory)),
            identityHeaderAliases = aliases,
        ).materialize(contract)

        val first = MockServerRequest.builder().build()
        val second = MockServerRequest.builder().build()
        binding.handlerFunction.handle(first).block()
        binding.handlerFunction.handle(second).block()

        val routeIdentity = first.attribute(RouteIdentity.ATTRIBUTE).get() as RouteIdentity
        second.attribute(RouteIdentity.ATTRIBUTE).get().assert().isSameAs(routeIdentity)
        routeIdentity.pathVariables.assert().containsExactlyInAnyOrder(MessageRecords.TENANT_ID, MessageRecords.ID)
        routeIdentity.aliases.assert().isEqualTo(aliases)
    }

    private fun routeContract(): HttpRouteContract {
        return HttpRouteContract(
            routeId = "test.route",
            method = "GET",
            path = "/test",
            handlerKey = "handler.key",
            handlerMetadata = HttpRouteHandlerMetadata.None
        )
    }
}

private class CapturingHttpRouteHandlerFunctionFactory(
    override val handlerKey: String
) : HttpRouteHandlerFunctionFactory {
    val handlerFunction = HandlerFunction<ServerResponse> {
        ServerResponse.ok().build()
    }
    var handled: ServerRequest? = null
    lateinit var createdContract: HttpRouteContract
    lateinit var createdMetadata: HttpRouteHandlerMetadata

    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata
    ): HandlerFunction<ServerResponse> {
        createdContract = contract
        createdMetadata = metadata
        return HandlerFunction { request ->
            handled = request
            handlerFunction.handle(request)
        }
    }
}
