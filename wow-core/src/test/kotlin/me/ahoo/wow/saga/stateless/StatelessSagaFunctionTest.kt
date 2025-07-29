package me.ahoo.wow.saga.stateless

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.Retry
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.command.factory.CommandBuilder
import me.ahoo.wow.command.factory.CommandBuilder.Companion.commandBuilder
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockChangeAggregate
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.test.SagaVerifier.sagaVerifier
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class StatelessSagaFunctionTest {

    @Test
    fun getAnnotation() {
        val delegate = mockk<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>> {
            every { getAnnotation(Retry::class.java) } returns null
            every { name } returns "test"
            every { supportedType } returns Any::class.java
            every { functionKind } returns FunctionKind.COMMAND
            every { contextName } returns "context"
            every { processor } returns "root"
            every { supportedTopics } returns emptySet()
        }
        val statelessSagaFunction = StatelessSagaFunction(delegate, mockk(), mockk())
        statelessSagaFunction.delegate.assert().isEqualTo(delegate)
        val retry = statelessSagaFunction.getAnnotation(Retry::class.java)
        retry.assert().isNull()
    }

    @Test
    fun multipleCommand() {
        sagaVerifier<MockSaga>()
            .`when`(MockAggregateCreated("data"))
            .expectNoError()
            .expectCommandCount(2)
            .verify()
    }

    @Test
    fun fluxCommand() {
        sagaVerifier<MockPublisherSaga>()
            .`when`(MockAggregateCreated("data"))
            .expectNoError()
            .expectCommandCount(2)
            .verify()
    }

    @Test
    fun returnCommandMessage() {
        sagaVerifier<MockReturnCommandMessageSaga>()
            .`when`(MockAggregateCreated("data"))
            .expectNoError()
            .expectCommand<MockCreateAggregate> {
                requestId.assert().isEqualTo(id)
            }
            .verify()
    }

    @Test
    fun returnCommandBuilder() {
        sagaVerifier<MockReturnBuilderMessageSaga>()
            .`when`(MockAggregateCreated("data"))
            .expectNoError()
            .expectCommand<MockCreateAggregate> {
                requestId.assert().isNotEqualTo(id)
            }
            .verify()
    }

    class MockSaga {
        @Suppress("UNUSED_PARAMETER")
        @OnEvent
        fun onEvent(event: MockAggregateCreated): List<Any> {
            return listOf(MockCreateAggregate("", ""), MockChangeAggregate("", ""))
        }
    }

    class MockPublisherSaga {
        @Suppress("UNUSED_PARAMETER")
        @OnEvent
        fun onEvent(event: MockAggregateCreated): Flux<Any> {
            return Flux.just(MockCreateAggregate("", ""), MockChangeAggregate("", ""))
        }
    }

    class MockReturnCommandMessageSaga {
        @Suppress("UNUSED_PARAMETER")
        @OnEvent
        fun onEvent(event: MockAggregateCreated): CommandMessage<MockCreateAggregate> {
            return MockCreateAggregate("", "").toCommandMessage()
        }
    }

    class MockReturnBuilderMessageSaga {
        @Suppress("UNUSED_PARAMETER")
        @OnEvent
        fun onEvent(event: MockAggregateCreated): CommandBuilder {
            return MockCreateAggregate("", "").commandBuilder()
        }
    }
}
