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
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.server.HandlerFunction
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
        binding.handlerFunction.assert().isSameAs(factory.handlerFunction)
        factory.createdContract.assert().isSameAs(contract)
        factory.createdMetadata.assert().isSameAs(contract.handlerMetadata)
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
    lateinit var createdContract: HttpRouteContract
    lateinit var createdMetadata: HttpRouteHandlerMetadata

    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata
    ): HandlerFunction<ServerResponse> {
        createdContract = contract
        createdMetadata = metadata
        return handlerFunction
    }
}
