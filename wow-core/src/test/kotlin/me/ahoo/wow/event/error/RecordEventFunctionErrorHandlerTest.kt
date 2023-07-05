package me.ahoo.wow.event.error

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.wow.api.messaging.FunctionKind
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.modeling.asNamedAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class RecordEventFunctionErrorHandlerTest {

    @Test
    fun handle() {
        val eventFunction = mockk<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>> {
            every { functionKind } returns FunctionKind.EVENT
            every { name } returns "test"
            every { processorName } returns "test"
            every { contextName } returns "test"
        }
        val exchange = mockk<DomainEventExchange<*>> {
            every { getEventFunction() } returns eventFunction
            every { message } returns mockk {
                every { id } returns "test"
                every {
                    aggregateId
                } returns "test.test".asNamedAggregate("test").asAggregateId()
            }
        }
        val throwable = RuntimeException("")
        val repository = mockk<EventFunctionErrorRepository> {
            every { record(any()) } returns Mono.empty()
        }
        RecordEventFunctionErrorHandler(repository).handle(exchange, throwable)
            .test()
            .verifyComplete()

        verify {
            repository.record(any())
        }
    }
}
