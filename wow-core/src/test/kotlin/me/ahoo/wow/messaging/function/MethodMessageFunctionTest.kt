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
package me.ahoo.wow.messaging.function

import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.infra.accessor.method.reactive.MonoMethodAccessor
import me.ahoo.wow.messaging.function.FunctionMetadataParser.asFunctionMetadata
import me.ahoo.wow.messaging.function.FunctionMetadataParser.asMonoFunctionMetadata
import me.ahoo.wow.tck.mock.MockCommandAggregate
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

internal class MethodMessageFunctionTest {
    @Test
    fun asMessageHandler() {
        val handler = MockCommandAggregate::class.java.getDeclaredMethod(
            "onCommand",
            MockCreateAggregate::class.java,
        ).asFunctionMetadata<MockCommandAggregate, Any>()
            .asMessageFunction<MockCommandAggregate, ServerCommandExchange<*>, Any>(
                MockCommandAggregate((MockStateAggregate(GlobalIdGenerator.generateAsString()))),
            )

        assertThat(handler, notNullValue())
        assertThat(
            handler,
            instanceOf(
                SimpleMethodMessageFunction::class.java,
            ),
        )
        assertThat(
            handler.supportedType,
            equalTo(
                MockCreateAggregate::class.java,
            ),
        )
    }

    @Test
    fun asMessageHandlerWhenInjectable() {
        val handler = MockWithInjectableFunction::class.java.getDeclaredMethod(
            "onEvent",
            MockEventBody::class.java,
            ExternalService::class.java,
        ).asFunctionMetadata<MockWithInjectableFunction, Any>()
            .asMessageFunction<MockWithInjectableFunction, DomainEventExchange<*>, Any>(
                MockWithInjectableFunction(),
            )

        assertThat(handler, notNullValue())
        assertThat(
            handler,
            instanceOf(
                InjectableMethodMessageFunction::class.java,
            ),
        )
        assertThat(
            handler.supportedType,
            equalTo(
                MockEventBody::class.java,
            ),
        )
    }

    @Test
    fun asMonoMessageHandler() {
        val handler = MockFunction::class.java.getDeclaredMethod(
            "onEvent",
            MockEventBody::class.java,
        ).asMonoFunctionMetadata<MockFunction, Any>()
            .asMessageFunction<MockFunction, ServerCommandExchange<*>, Any>(
                MockFunction(),
            )

        assertThat(handler, notNullValue())
        assertThat(
            handler,
            instanceOf(
                SimpleMethodMessageFunction::class.java,
            ),
        )
        assertThat(
            handler.supportedType,
            equalTo(
                MockEventBody::class.java,
            ),
        )
        assertThat(
            handler.metadata.accessor,
            instanceOf(
                MonoMethodAccessor::class.java,
            ),
        )
    }
}
