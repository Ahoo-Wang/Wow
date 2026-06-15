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

package me.ahoo.wow.webflux.route.command

import com.sun.security.auth.UserPrincipal
import io.mockk.spyk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.factory.SimpleCommandBuilderRewriterRegistry
import me.ahoo.wow.command.factory.SimpleCommandMessageFactory
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.example.api.cart.ChangeQuantity
import me.ahoo.wow.example.api.cart.RemoveCartItem
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.example.domain.cart.CartState
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.openapi.metadata.commandRouteMetadata
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.test.SagaVerifier
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.command.extractor.DefaultCommandBuilderExtractor
import me.ahoo.wow.webflux.route.command.extractor.DefaultCommandMessageExtractor
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

class CartCommandHandlerFunctionTest {

    companion object {
        val CART_AGGREGATE_METADATA = aggregateMetadata<Cart, CartState>()
        val CART_AGGREGATE_ROUTE_METADATA = Cart::class.java.aggregateRouteMetadata()
    }

    @Test
    fun `should send AddCartItem command and return sent stage`() {
        val commandGateway = spyk<CommandGateway>(SagaVerifier.defaultCommandGateway())
        val commandRouteMetadata = commandRouteMetadata<AddCartItem>()
        val handlerFunction = CommandHandlerFunction(
            CART_AGGREGATE_ROUTE_METADATA,
            commandRouteMetadata,
            commandGateway,
            DefaultCommandMessageExtractor(
                SimpleCommandMessageFactory(NoOpValidator, SimpleCommandBuilderRewriterRegistry()),
                DefaultCommandBuilderExtractor
            ),
            WebFluxRequestExceptionHandler(),
        )

        val aggregateId = generateGlobalId()
        val request = MockServerRequest.builder()
            .method(HttpMethod.POST)
            .pathVariable(MessageRecords.TENANT_ID, generateGlobalId())
            .pathVariable(MessageRecords.OWNER_ID, aggregateId)
            .principal(UserPrincipal(generateGlobalId()))
            .header(CommandComponent.Header.WAIT_STAGE, CommandStage.SENT.name)
            .body(
                AddCartItem(
                    productId = "product-1",
                    quantity = 2,
                ).toMono()
            )
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.OK)
            }.verifyComplete()

        verify {
            commandGateway.sendAndWait<Any>(any(), any())
        }
    }

    @Test
    fun `should send ChangeQuantity command through handler`() {
        val commandGateway = spyk<CommandGateway>(SagaVerifier.defaultCommandGateway())
        val commandRouteMetadata = commandRouteMetadata<ChangeQuantity>()
        val handlerFunction = CommandHandlerFunction(
            CART_AGGREGATE_ROUTE_METADATA,
            commandRouteMetadata,
            commandGateway,
            DefaultCommandMessageExtractor(
                SimpleCommandMessageFactory(NoOpValidator, SimpleCommandBuilderRewriterRegistry()),
                DefaultCommandBuilderExtractor
            ),
            WebFluxRequestExceptionHandler(),
        )

        val aggregateId = generateGlobalId()
        val request = MockServerRequest.builder()
            .method(HttpMethod.PUT)
            .pathVariable(MessageRecords.ID, aggregateId)
            .pathVariable(MessageRecords.TENANT_ID, generateGlobalId())
            .pathVariable(MessageRecords.OWNER_ID, aggregateId)
            .principal(UserPrincipal(generateGlobalId()))
            .header(CommandComponent.Header.WAIT_STAGE, CommandStage.SENT.name)
            .body(
                ChangeQuantity(
                    productId = "product-1",
                    quantity = 5,
                ).toMono()
            )
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.OK)
            }.verifyComplete()

        verify {
            commandGateway.sendAndWait<Any>(any(), any())
        }
    }

    @Test
    fun `should send RemoveCartItem command through handler`() {
        val commandGateway = spyk<CommandGateway>(SagaVerifier.defaultCommandGateway())
        val commandRouteMetadata = commandRouteMetadata<RemoveCartItem>()
        val handlerFunction = CommandHandlerFunction(
            CART_AGGREGATE_ROUTE_METADATA,
            commandRouteMetadata,
            commandGateway,
            DefaultCommandMessageExtractor(
                SimpleCommandMessageFactory(NoOpValidator, SimpleCommandBuilderRewriterRegistry()),
                DefaultCommandBuilderExtractor
            ),
            WebFluxRequestExceptionHandler(),
        )

        val aggregateId = generateGlobalId()
        val request = MockServerRequest.builder()
            .method(HttpMethod.DELETE)
            .pathVariable(MessageRecords.TENANT_ID, generateGlobalId())
            .pathVariable(MessageRecords.OWNER_ID, aggregateId)
            .principal(UserPrincipal(generateGlobalId()))
            .header(CommandComponent.Header.WAIT_STAGE, CommandStage.SENT.name)
            .body(
                RemoveCartItem(
                    productIds = setOf("product-1"),
                ).toMono()
            )
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.OK)
            }.verifyComplete()

        verify {
            commandGateway.sendAndWait<Any>(any(), any())
        }
    }

    @Test
    fun `should use AGGREGATE_ID owner to resolve aggregate id from owner id`() {
        val aggregateRouteMetadata = CART_AGGREGATE_ROUTE_METADATA
        // Cart uses @AggregateRoute(owner = AGGREGATE_ID), so owner == aggregate ID
        aggregateRouteMetadata.owner.assert().isEqualTo(
            me.ahoo.wow.api.annotation.AggregateRoute.Owner.AGGREGATE_ID
        )
    }
}
