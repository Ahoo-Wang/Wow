package me.ahoo.wow.saga.stateless

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.Retry
import me.ahoo.wow.api.messaging.FunctionKind
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockChangeAggregate
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.test.SagaVerifier.sagaVerifier
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
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
            every { accessorName } returns "root"
            every { supportedTopics } returns emptySet()
        }
        MatcherAssert.assertThat(
            StatelessSagaFunction(delegate, mockk(), mockk()).getAnnotation(Retry::class.java),
            Matchers.nullValue()
        )
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
}
