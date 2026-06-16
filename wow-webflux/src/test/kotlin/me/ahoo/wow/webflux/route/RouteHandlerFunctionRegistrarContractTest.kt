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

class RouteHandlerFunctionRegistrarContractTest {

    @Test
    fun `should lookup constructor provided http factory by handler key`() {
        val factory = TestHttpRouteHandlerFunctionFactory("handler.key")
        val registrar = RouteHandlerFunctionRegistrar(httpFactories = listOf(factory))

        val found = registrar.getHttpFactory("handler.key")

        found.assert().isSameAs(factory)
    }

    @Test
    fun `should let later constructor http factory overwrite earlier factory for same handler key`() {
        val factory1 = TestHttpRouteHandlerFunctionFactory("handler.key")
        val factory2 = TestHttpRouteHandlerFunctionFactory("handler.key")
        val registrar = RouteHandlerFunctionRegistrar(httpFactories = listOf(factory1, factory2))

        val found = registrar.getHttpFactory("handler.key")

        found.assert().isSameAs(factory2)
    }

    @Test
    fun `should let registered http factory overwrite existing factory for same handler key`() {
        val factory1 = TestHttpRouteHandlerFunctionFactory("handler.key")
        val factory2 = TestHttpRouteHandlerFunctionFactory("handler.key")
        val registrar = RouteHandlerFunctionRegistrar(httpFactories = listOf(factory1))

        registrar.registerHttpFactory(factory2)

        val found = registrar.getHttpFactory("handler.key")
        found.assert().isSameAs(factory2)
    }

    @Test
    fun `should register http factory through explicit http registration`() {
        val factory = TestHttpRouteHandlerFunctionFactory("handler.key")
        val registrar = RouteHandlerFunctionRegistrar()

        registrar.registerHttpFactory(factory)

        registrar.getHttpFactory(factory.handlerKey).assert().isSameAs(factory)
    }

    @Test
    fun `should use contract handler metadata by default when creating handler`() {
        val factory = TestHttpRouteHandlerFunctionFactory("handler.key")
        val contract = HttpRouteContract(
            routeId = "route",
            method = "GET",
            path = "/route",
            handlerKey = "handler.key",
            handlerMetadata = HttpRouteHandlerMetadata.None
        )

        factory.create(contract)

        factory.createdContract.assert().isSameAs(contract)
        factory.createdMetadata.assert().isSameAs(contract.handlerMetadata)
    }
}

private class TestHttpRouteHandlerFunctionFactory(
    override val handlerKey: String
) : HttpRouteHandlerFunctionFactory {
    lateinit var createdContract: HttpRouteContract
    lateinit var createdMetadata: HttpRouteHandlerMetadata

    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata
    ): HandlerFunction<ServerResponse> {
        createdContract = contract
        createdMetadata = metadata
        return HandlerFunction {
            ServerResponse.ok().build()
        }
    }
}
