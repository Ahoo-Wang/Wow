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
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.test.SagaVerifier
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.testGlobalRouteContract
import me.ahoo.wow.webflux.route.command.extractor.DefaultCommandBuilderExtractor
import me.ahoo.wow.webflux.route.command.extractor.DefaultCommandMessageExtractor
import me.ahoo.wow.webflux.route.policy.CommandWaitPolicy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import reactor.util.function.Tuples

class CartCommandFacadeHandlerFunctionTest {

    @Test
    fun `should dispatch AddCartItem via facade using command type header`() {
        val commandGateway = spyk<CommandGateway>(SagaVerifier.defaultCommandGateway())
        val cartRouteMetadata = Cart::class.java.aggregateRouteMetadata()

        val handlerFunction = CommandFacadeHandlerFunctionFactory(
            commandGateway = commandGateway,
            commandMessageExtractor = DefaultCommandMessageExtractor(
                SimpleCommandMessageFactory(NoOpValidator, SimpleCommandBuilderRewriterRegistry()),
                DefaultCommandBuilderExtractor
            ),
            exceptionHandler = WebFluxRequestExceptionHandler(),
            commandWaitPolicy = CommandWaitPolicy(DEFAULT_TIME_OUT),
        ).create(testGlobalRouteContract(BuiltInHttpRouteHandlerKeys.Global.COMMAND_FACADE))

        val aggregateId = generateGlobalId()
        val request = MockServerRequest.builder()
            .method(HttpMethod.POST)
            .pathVariable(MessageRecords.TENANT_ID, generateGlobalId())
            .pathVariable(MessageRecords.OWNER_ID, aggregateId)
            .principal(UserPrincipal(generateGlobalId()))
            .header(CommandComponent.Header.WAIT_STAGE, CommandStage.SENT.name)
            .header(CommandComponent.Header.COMMAND_TYPE, AddCartItem::class.java.name)
            .body(
                Tuples.of(
                    AddCartItem(
                        productId = "product-1",
                        quantity = 3,
                    ) as Any,
                    cartRouteMetadata
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
}
