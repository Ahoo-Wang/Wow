package me.ahoo.wow.saga.stateless

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.annotation.Retry
import me.ahoo.wow.api.messaging.FunctionKind
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.messaging.function.MessageFunction
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
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
        MatcherAssert.assertThat(
            StatelessSagaFunction(delegate, mockk()).getAnnotation(Retry::class.java),
            Matchers.nullValue()
        )
    }
}
