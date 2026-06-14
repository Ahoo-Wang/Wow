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

package me.ahoo.wow.benchmark.webflux

import com.sun.security.auth.UserPrincipal
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkCommands
import me.ahoo.wow.command.COMMAND_GATEWAY_FUNCTION
import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.command.factory.SimpleCommandBuilderRewriterRegistry
import me.ahoo.wow.command.factory.SimpleCommandMessageFactory
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.openapi.metadata.CommandRouteMetadata
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.openapi.metadata.commandRouteMetadata
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.webflux.route.command.extractor.DefaultCommandBuilderExtractor
import me.ahoo.wow.webflux.route.command.extractor.DefaultCommandMessageExtractor
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.codec.HttpMessageWriter
import org.springframework.http.codec.ServerSentEventHttpMessageWriter
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.result.view.ViewResolver
import reactor.kotlin.core.publisher.toMono

internal object WebFluxBenchmarkSupport {
    val cartAggregateRouteMetadata: AggregateRouteMetadata<Cart> = Cart::class.java.aggregateRouteMetadata()
    val addCartItemRouteMetadata: CommandRouteMetadata<AddCartItem> = commandRouteMetadata()
    val commandMessageExtractor = DefaultCommandMessageExtractor(
        SimpleCommandMessageFactory(
            NoOpValidator,
            SimpleCommandBuilderRewriterRegistry(),
        ),
        DefaultCommandBuilderExtractor,
    )

    val sseResponseContext: ServerResponse.Context = object : ServerResponse.Context {
        override fun messageWriters(): List<HttpMessageWriter<*>> {
            return listOf(ServerSentEventHttpMessageWriter())
        }

        override fun viewResolvers(): List<ViewResolver> {
            return emptyList()
        }
    }

    fun addCartItemRequest(): ServerRequest {
        return MockServerRequest.builder()
            .method(HttpMethod.POST)
            .pathVariable(MessageRecords.TENANT_ID, "benchmark-tenant")
            .pathVariable(MessageRecords.OWNER_ID, BenchmarkAggregates.FIXED_AGGREGATE_ID)
            .principal(UserPrincipal("benchmark-user"))
            .header(CommandComponent.Header.WAIT_STAGE, CommandStage.SENT.name)
            .body(AddCartItem(productId = "productId", quantity = 1).toMono())
    }

    fun jsonRequest(): ServerRequest {
        return MockServerRequest.builder().build()
    }

    fun sseRequest(): ServerRequest {
        return MockServerRequest.builder()
            .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
            .build()
    }

    fun sseExchange(): MockServerWebExchange {
        val request = MockServerHttpRequest.get("/benchmark-webflux-sse").build()
        return MockServerWebExchange.builder(request).build()
    }

    fun commandResult(commandMessage: CommandMessage<*> = BenchmarkCommands.fixedAggregateAddCartItem()): CommandResult {
        return CommandResult(
            id = commandMessage.commandId,
            waitCommandId = commandMessage.commandId,
            stage = CommandStage.SENT,
            contextName = commandMessage.contextName,
            aggregateName = commandMessage.aggregateName,
            tenantId = commandMessage.aggregateId.tenantId,
            aggregateId = commandMessage.aggregateId.id,
            requestId = commandMessage.requestId,
            commandId = commandMessage.commandId,
            function = COMMAND_GATEWAY_FUNCTION,
        )
    }

    fun responsePayloads(count: Int = 10): List<ResponsePayload> {
        return (1..count).map { index ->
            ResponsePayload(
                id = "payload-$index",
                name = "payload-name-$index",
                value = index,
            )
        }
    }
}

internal data class ResponsePayload(
    val id: String,
    val name: String,
    val value: Int,
)
