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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.annotation.Retry
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.infra.accessor.method.reactive.MonoMethodAccessor
import me.ahoo.wow.messaging.function.FunctionMetadataParser.toFunctionMetadata
import me.ahoo.wow.messaging.function.FunctionMetadataParser.toMonoFunctionMetadata
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockCommandAggregate
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

internal class MethodMessageFunctionTest {
    @Test
    fun toMessageFunction() {
        val messageFunction = MockCommandAggregate::class.java.getDeclaredMethod(
            "onCommand",
            MockCreateAggregate::class.java,
        ).toFunctionMetadata<MockCommandAggregate, MockAggregateCreated>()
            .toMessageFunction<MockCommandAggregate, ServerCommandExchange<*>, MockAggregateCreated>(
                MockCommandAggregate((MockStateAggregate(GlobalIdGenerator.generateAsString()))),
            )
        assertThat(messageFunction, notNullValue())
        val serverCommandExchange = mockk<ServerCommandExchange<MockCreateAggregate>> {
            every { message } returns MockCreateAggregate("id", "data").toCommandMessage()
        }
        messageFunction(serverCommandExchange)
        val created = messageFunction.handle(serverCommandExchange)
        assertThat(created.data, equalTo("data"))

        assertThat(
            messageFunction,
            instanceOf(
                SimpleMethodMessageFunction::class.java,
            ),
        )
        assertThat(
            messageFunction.supportedType,
            equalTo(
                MockCreateAggregate::class.java,
            ),
        )
        assertThat(
            messageFunction.supportedTopics,
            equalTo(
                emptySet(),
            ),
        )
        assertThat(
            messageFunction.processorName,
            equalTo(
                "MockCommandAggregate"
            ),
        )
        val retry = messageFunction.getAnnotation(Retry::class.java)
        assertThat(retry, nullValue())
        val onCommand = messageFunction.getAnnotation(OnCommand::class.java)
        assertThat(onCommand, notNullValue())
    }

    @Test
    fun toMessageFunctionWhenInjectable() {
        val messageFunction = MockWithInjectableFunction::class.java.getDeclaredMethod(
            "onEvent",
            MockEventBody::class.java,
            ExternalService::class.java,
        ).toFunctionMetadata<MockWithInjectableFunction, Any>()
            .toMessageFunction<MockWithInjectableFunction, DomainEventExchange<*>, Any>(
                MockWithInjectableFunction(),
            )

        assertThat(messageFunction, notNullValue())
        assertThat(
            messageFunction,
            instanceOf(
                InjectableMethodMessageFunction::class.java,
            ),
        )
        assertThat(
            messageFunction.supportedType,
            equalTo(
                MockEventBody::class.java,
            ),
        )
        val retry = messageFunction.getAnnotation(Retry::class.java)
        assertThat(retry, nullValue())
    }

    @Test
    fun toMonoMessageFunction() {
        val messageFunction = MockFunction::class.java.getDeclaredMethod(
            "onEvent",
            MockEventBody::class.java,
        ).toMonoFunctionMetadata<MockFunction, Any>()
            .toMessageFunction<MockFunction, ServerCommandExchange<*>, Any>(
                MockFunction(),
            )

        assertThat(messageFunction, notNullValue())
        assertThat(
            messageFunction,
            instanceOf(
                SimpleMethodMessageFunction::class.java,
            ),
        )
        assertThat(
            messageFunction.supportedType,
            equalTo(
                MockEventBody::class.java,
            ),
        )
        assertThat(
            messageFunction.metadata.accessor,
            instanceOf(
                MonoMethodAccessor::class.java,
            ),
        )
        val retry = messageFunction.getAnnotation(Retry::class.java)
        assertThat(retry, notNullValue())
        assertThat(retry!!.enabled, equalTo(true))
        assertThat(retry!!.maxRetries, equalTo(Retry.DEFAULT_MAX_RETRIES))
        assertThat(retry!!.minBackoff, equalTo(Retry.DEFAULT_MIN_BACKOFF))
        assertThat(retry!!.executionTimeout, equalTo(Retry.DEFAULT_EXECUTION_TIMEOUT))
    }
}
