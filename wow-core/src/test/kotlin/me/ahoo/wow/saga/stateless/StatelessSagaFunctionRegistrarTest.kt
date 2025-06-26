package me.ahoo.wow.saga.stateless

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.StatelessSaga
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.command.factory.SimpleCommandBuilderRewriterRegistry
import me.ahoo.wow.command.factory.SimpleCommandMessageFactory
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.configuration.requiredNamedAggregate
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.test.SagaVerifier
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
            SimpleCommandMessageFactory(NoOpValidator, SimpleCommandBuilderRewriterRegistry())
        )
        functionRegistrar.registerProcessor(MockSaga())
        functionRegistrar.supportedFunctions(message).toSet().assert().hasSize(1)
    }
}

@StatelessSaga
class MockSaga {

    @Suppress("UnusedParameter")
    @OnEvent
    fun onCreated(event: MockAggregateCreated) = Unit
}
