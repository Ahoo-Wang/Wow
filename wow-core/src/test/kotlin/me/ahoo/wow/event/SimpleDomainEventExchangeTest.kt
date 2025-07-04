package me.ahoo.wow.event

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.messaging.function.MessageFunction
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

class SimpleDomainEventExchangeTest {

    @Test
    fun main() {
        val exchange = SimpleDomainEventExchange(
            mockk<DomainEvent<Any>> {
                every { version } returns 1
            }
        )
        val mockEventFunction = MockEventFunction()
        exchange.setFunction(mockEventFunction).getEventFunction().assert().isNotNull()
        exchange.getFunction().assert().isNotNull()
        exchange.getAggregateVersion().assert().isOne()
    }

    class MockEventFunction : MessageFunction<Any, DomainEventExchange<*>, Mono<Void>> {
        override val name: String
            get() = "Mock"
        override val supportedType: Class<*>
            get() = Any::class.java
        override val supportedTopics: Set<NamedAggregate>
            get() = emptySet()
        override val processor: Any
            get() = this

        override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? {
            return null
        }

        override fun invoke(exchange: DomainEventExchange<*>): Mono<Void> {
            return Mono.empty()
        }

        override val contextName: String
            get() = "mock"
        override val functionKind: FunctionKind
            get() = FunctionKind.EVENT
    }
}
