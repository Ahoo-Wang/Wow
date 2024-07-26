package me.ahoo.wow.event

import io.mockk.mockk
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.messaging.function.MessageFunction
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

class ExchangeEventFunctionKtTest {

    @Test
    fun setEventFunction() {
        val exchange = SimpleDomainEventExchange(mockk<DomainEvent<Any>>())
        val mockEventFunction = MockEventFunction()
        assertThat(exchange.setFunction(mockEventFunction).getEventFunction(), notNullValue())
        assertThat(exchange.getFunction(), notNullValue())
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
