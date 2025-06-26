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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.annotation.Retry
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.infra.accessor.function.reactive.MonoFunctionAccessor
import me.ahoo.wow.messaging.function.FunctionMetadataParser.toFunctionMetadata
import me.ahoo.wow.messaging.function.FunctionMetadataParser.toMonoFunctionMetadata
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockCommandAggregate
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test
import kotlin.reflect.jvm.kotlinFunction

internal class MethodMessageFunctionTest {
    @Test
    fun toMessageFunction() {
        val commandFunction = MockCommandAggregate::class.java.getDeclaredMethod(
            "onCommand",
            MockCreateAggregate::class.java,
        ).kotlinFunction!!
        val messageFunction = commandFunction.toFunctionMetadata<MockCommandAggregate, MockAggregateCreated>()
            .toMessageFunction<MockCommandAggregate, ServerCommandExchange<*>, MockAggregateCreated>(
                MockCommandAggregate((MockStateAggregate(GlobalIdGenerator.generateAsString()))),
            )
        val serverCommandExchange = mockk<ServerCommandExchange<MockCreateAggregate>> {
            every { message } returns MockCreateAggregate("id", "data").toCommandMessage()
        }
        messageFunction(serverCommandExchange)
        val created = messageFunction.handle(serverCommandExchange)
        created.data.assert().isEqualTo("data")
        messageFunction.assert().isInstanceOf(SimpleMessageFunctionAccessor::class.java)
        messageFunction.supportedType.assert().isEqualTo(MockCreateAggregate::class.java)
        messageFunction.supportedTopics.isEmpty()
        messageFunction.processorName.assert().isEqualTo("MockCommandAggregate")
        messageFunction.name.assert().isEqualTo("onCommand")
        messageFunction.qualifiedName.assert().isEqualTo("MockCommandAggregate.onCommand(MockCreateAggregate)")
        val retry = messageFunction.getAnnotation(Retry::class.java)
        retry.assert().isNull()
        val onCommand = messageFunction.getAnnotation(OnCommand::class.java)
        onCommand.assert().isNotNull()
    }

    @Test
    fun toMessageFunctionWhenInjectable() {
        val messageFunction = MockWithInjectableFunction::onEvent.toFunctionMetadata<MockWithInjectableFunction, Any>()
            .toMessageFunction<MockWithInjectableFunction, DomainEventExchange<*>, Any>(
                MockWithInjectableFunction(),
            )
        messageFunction.assert().isNotNull()

        messageFunction.assert()
            .isInstanceOf(
                InjectableMessageFunctionAccessor::class.java,
            )

        messageFunction.supportedType.assert().isEqualTo(

            MockEventBody::class.java,
        )

        val retry = messageFunction.getAnnotation(Retry::class.java)
        retry.assert().isNull()
    }

    @Test
    fun toMonoMessageFunction() {
        val messageFunction = MockFunction::onEvent.toMonoFunctionMetadata<MockFunction, Any>()
            .toMessageFunction<MockFunction, ServerCommandExchange<*>, Any>(
                MockFunction(),
            )
        messageFunction.assert().isNotNull()

        messageFunction.assert()
            .isInstanceOf(
                SimpleMessageFunctionAccessor::class.java,
            )

        messageFunction.supportedType
            .assert().isEqualTo(
                MockEventBody::class.java,
            )

        messageFunction.metadata.accessor.assert().isInstanceOf(
            MonoFunctionAccessor::class.java
        )

        val retry = messageFunction.getAnnotation(Retry::class.java)
        retry.assert().isNotNull()
        retry!!.enabled.assert().isEqualTo(true)
        retry.maxRetries.assert().isEqualTo(Retry.DEFAULT_MAX_RETRIES)
        retry.minBackoff.assert().isEqualTo(Retry.DEFAULT_MIN_BACKOFF)
        retry.executionTimeout.assert().isEqualTo(Retry.DEFAULT_EXECUTION_TIMEOUT)
    }
}
