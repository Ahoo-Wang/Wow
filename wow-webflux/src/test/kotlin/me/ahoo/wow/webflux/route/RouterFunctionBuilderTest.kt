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
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.openapi.RouteSpec
import me.ahoo.wow.openapi.RouterSpecs
import me.ahoo.wow.openapi.aggregate.state.LoadAggregateRouteSpec
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerResponse

class RouteHandlerFunctionRegistrarTest {

    @Test
    fun `should lookup constructor provided factory by spec type`() {
        val factory = TestRouteHandlerFunctionFactory(LoadAggregateRouteSpec::class.java)
        val registrar = RouteHandlerFunctionRegistrar(listOf(factory))

        val found = registrar.getFactory(loadAggregateSpec())
        found.assert().isNotNull()
        found.assert().isSameAs(factory)
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
    fun `should let later constructor factory overwrite earlier factory for same spec type`() {
        val factory1 = TestRouteHandlerFunctionFactory(LoadAggregateRouteSpec::class.java)
        val factory2 = TestRouteHandlerFunctionFactory(LoadAggregateRouteSpec::class.java)
        val registrar = RouteHandlerFunctionRegistrar(listOf(factory1, factory2))

        val found = registrar.getFactory(loadAggregateSpec())
        found.assert().isSameAs(factory2)
    }
}

class RouterFunctionBuilderTest {

    @Test
    fun `should build router function with manually provided specs`() {
        val loadAggregateSpec = loadAggregateSpec()
        val routerSpecs = RouterSpecs(
            MOCK_AGGREGATE_METADATA,
            routes = mutableListOf(loadAggregateSpec),
        )
        val contract = routerSpecs.toRouteCatalog().routes.single()
        val factory = TestBuilderHttpRouteHandlerFunctionFactory(contract.handlerKey)
        val registrar = RouteHandlerFunctionRegistrar(httpFactories = listOf(factory))

        val builder = RouterFunctionBuilder(routerSpecs, registrar)
        val routerFunction = builder.build()

        routerFunction.assert().isNotNull()
        factory.createdContract.assert().isEqualTo(contract)
        factory.createdMetadata.assert().isSameAs(contract.handlerMetadata)
    }

    @Test
    fun `should report route details when factory is missing`() {
        val loadAggregateSpec = loadAggregateSpec()
        val routerSpecs = RouterSpecs(
            MOCK_AGGREGATE_METADATA,
            routes = mutableListOf(loadAggregateSpec),
        )
        val builder = RouterFunctionBuilder(routerSpecs, RouteHandlerFunctionRegistrar())
        val contract = routerSpecs.toRouteCatalog().routes.single()

        assertThrownBy<IllegalArgumentException> {
            builder.build()
        }.hasMessage(
            "HttpRouteHandlerFunctionFactory not found - " +
                "handlerKey:[${contract.handlerKey}], " +
                "method:[${contract.method}], " +
                "path:[${contract.path}], " +
                "routeId:[${contract.routeId}]."
        )
    }
}

private fun loadAggregateSpec(): LoadAggregateRouteSpec {
    return LoadAggregateRouteSpec(
        MOCK_AGGREGATE_METADATA,
        aggregateRouteMetadata = MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata(),
        componentContext = OpenAPIComponentContext.default()
    )
}

private class TestRouteHandlerFunctionFactory<R : RouteSpec>(
    override val supportedSpec: Class<R>
) : RouteHandlerFunctionFactory<R> {
    override fun create(spec: R): HandlerFunction<ServerResponse> {
        return HandlerFunction {
            ServerResponse.ok().build()
        }
    }
}

private class TestBuilderHttpRouteHandlerFunctionFactory(
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
