package me.ahoo.wow.saga.stateless

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.StatelessSaga
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.command.factory.SimpleCommandMessageFactory
import me.ahoo.wow.command.factory.SimpleCommandOptionsExtractorRegistry
import me.ahoo.wow.configuration.requiredNamedAggregate
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.test.SagaVerifier
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class StatelessSagaFunctionRegistrarTest {
    private val message = mockk<DomainEvent<MockAggregateCreated>> {
        every { body } returns MockAggregateCreated("data")
        every { contextName } returns requiredNamedAggregate<MockAggregateCreated>().contextName
        every { aggregateName } returns requiredNamedAggregate<MockAggregateCreated>().aggregateName
    }

    @Test
    fun register() {
        val functionRegistrar = StatelessSagaFunctionRegistrar(
            SagaVerifier.defaultCommandGateway(),
            SimpleCommandMessageFactory(SimpleCommandOptionsExtractorRegistry())
        )
        functionRegistrar.registerProcessor(MockSaga())
        assertThat(functionRegistrar.supportedFunctions(message).toSet(), hasSize(1))
    }
}

@StatelessSaga
class MockSaga {

    @Suppress("UnusedParameter")
    @OnEvent
    fun onCreated(event: MockAggregateCreated) = Unit
}
