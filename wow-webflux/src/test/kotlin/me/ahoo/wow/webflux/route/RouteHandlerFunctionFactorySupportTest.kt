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
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.openapi.metadata.commandRouteMetadata
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerResponse

class RouteHandlerFunctionFactorySupportTest {

    private val aggregateRouteMetadata =
        MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata()

    @Test
    fun `aggregate support should pass aggregate metadata to typed creator`() {
        val factory = TestAggregateFactory("aggregate.handler")
        val metadata = HttpRouteHandlerMetadata.Aggregate(aggregateRouteMetadata)
        val contract = routeContract(factory.handlerKey, metadata)

        factory.create(contract)

        factory.createdMetadata.assert().isSameAs(metadata)
    }

    @Test
    fun `aggregate support should reject non aggregate metadata`() {
        val factory = TestAggregateFactory("aggregate.handler")
        val contract = routeContract(factory.handlerKey, HttpRouteHandlerMetadata.None)

        val error = assertThrows<IllegalStateException> {
            factory.create(contract)
        }

        error.message.assert().contains("handlerKey:[aggregate.handler]")
        error.message.assert().contains(HttpRouteHandlerMetadata.Aggregate::class.java.name)
    }

    @Test
    fun `command support should pass command metadata to typed creator`() {
        val factory = TestCommandFactory("command.handler")
        val metadata = HttpRouteHandlerMetadata.Command(
            aggregateRouteMetadata = aggregateRouteMetadata,
            commandRouteMetadata = MockCreateAggregate::class.java.commandRouteMetadata()
        )
        val contract = routeContract(factory.handlerKey, metadata)

        factory.create(contract)

        factory.createdMetadata.assert().isSameAs(metadata)
    }

    @Test
    fun `command support should reject non command metadata`() {
        val factory = TestCommandFactory("command.handler")
        val contract = routeContract(factory.handlerKey, HttpRouteHandlerMetadata.Aggregate(aggregateRouteMetadata))

        val error = assertThrows<IllegalStateException> {
            factory.create(contract)
        }

        error.message.assert().contains("handlerKey:[command.handler]")
        error.message.assert().contains(HttpRouteHandlerMetadata.Command::class.java.name)
    }

    @Test
    fun `no metadata support should ignore metadata shape`() {
        val factory = TestNoMetadataFactory("global.handler")
        val contract = routeContract(factory.handlerKey, HttpRouteHandlerMetadata.Aggregate(aggregateRouteMetadata))

        factory.create(contract)

        factory.createdContract.assert().isSameAs(contract)
    }

    private fun routeContract(
        handlerKey: String,
        metadata: HttpRouteHandlerMetadata
    ): HttpRouteContract {
        return HttpRouteContract(
            routeId = "test.route",
            method = "GET",
            path = "/test",
            handlerKey = handlerKey,
            handlerMetadata = metadata
        )
    }
}

private class TestAggregateFactory(
    handlerKey: String
) : AggregateRouteHandlerFunctionFactorySupport(handlerKey) {
    lateinit var createdMetadata: HttpRouteHandlerMetadata.Aggregate

    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata.Aggregate
    ): HandlerFunction<ServerResponse> {
        createdMetadata = metadata
        return HandlerFunction { ServerResponse.ok().build() }
    }
}

private class TestCommandFactory(
    handlerKey: String
) : CommandRouteHandlerFunctionFactorySupport(handlerKey) {
    lateinit var createdMetadata: HttpRouteHandlerMetadata.Command

    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata.Command
    ): HandlerFunction<ServerResponse> {
        createdMetadata = metadata
        return HandlerFunction { ServerResponse.ok().build() }
    }
}

private class TestNoMetadataFactory(
    handlerKey: String
) : NoMetadataRouteHandlerFunctionFactorySupport(handlerKey) {
    lateinit var createdContract: HttpRouteContract

    override fun create(contract: HttpRouteContract): HandlerFunction<ServerResponse> {
        createdContract = contract
        return HandlerFunction { ServerResponse.ok().build() }
    }
}
