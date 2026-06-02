package me.ahoo.wow.saga.stateless

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.Retry
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.factory.CommandBuilder
import me.ahoo.wow.command.factory.CommandBuilder.Companion.commandBuilder
import me.ahoo.wow.command.factory.CommandMessageFactory
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.SimpleDomainEventExchange
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockChangeAggregate
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.test.SagaVerifier.sagaVerifier
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

class StatelessSagaFunctionTest {

    @Test
    fun `should get annotation`() {
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
    fun `should multiple command`() {
        sagaVerifier<MockSaga>()
            .whenEvent(MockAggregateCreated("data"))
            .expectNoError()
            .expectCommandCount(2)
            .verify()
    }

    @Test
    fun `should flux command`() {
        sagaVerifier<MockPublisherSaga>()
            .whenEvent(MockAggregateCreated("data"))
            .expectNoError()
            .expectCommandCount(2)
            .verify()
    }

    @Test
    fun `should return command message`() {
        sagaVerifier<MockReturnCommandMessageSaga>()
            .whenEvent(MockAggregateCreated("data"))
            .expectNoError()
            .expectCommand<MockCreateAggregate> {
                requestId.assert().isEqualTo(id)
            }
            .verify()
    }

    @Test
    fun `should return command builder`() {
        sagaVerifier<MockReturnBuilderMessageSaga>()
            .whenEvent(MockAggregateCreated("data"))
            .expectNoError()
            .expectCommand<MockCreateAggregate> {
                requestId.assert().isNotEqualTo(id)
            }
            .verify()
    }

    @Test
    fun `should preserve iterable command order when command creation is asynchronous`() {
        val sentBodyTypes = mutableListOf<Class<*>>()
        val commandGateway = mockk<CommandGateway> {
            every { send(any<CommandMessage<*>>()) } answers {
                sentBodyTypes.add(firstArg<CommandMessage<*>>().body.javaClass)
                Mono.empty()
            }
        }
        val commandMessageFactory = object : CommandMessageFactory {
            override fun <TARGET : Any> create(commandBuilder: CommandBuilder): Mono<CommandMessage<TARGET>> {
                val commandMessage = commandBuilder.toCommandMessage<TARGET>()
                val delay = if (commandMessage.body is MockCreateAggregate) {
                    Duration.ofMillis(50)
                } else {
                    Duration.ZERO
                }
                return Mono.delay(delay).thenReturn(commandMessage)
            }
        }
        val delegate = object : MessageFunction<Any, DomainEventExchange<*>, Mono<*>> {
            override val contextName: String = "context"
            override val name: String = "onEvent"
            override val processor: Any = "processor"
            override val supportedType: Class<*> = MockAggregateCreated::class.java
            override val supportedTopics = emptySet<me.ahoo.wow.api.modeling.NamedAggregate>()
            override val functionKind: FunctionKind = FunctionKind.EVENT
            override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = null
            override fun invoke(exchange: DomainEventExchange<*>): Mono<*> = Mono.just<Any>(
                listOf(MockCreateAggregate("", ""), MockChangeAggregate("", ""))
            )
        }
        val statelessSagaFunction = StatelessSagaFunction(delegate, commandGateway, commandMessageFactory)
        val upstream = MockCreateAggregate(generateGlobalId(), "data").toCommandMessage()
        val event = MockAggregateCreated("data").toDomainEventStream(
            upstream = upstream,
            aggregateVersion = 1,
        ).body.first()

        statelessSagaFunction.invoke(SimpleDomainEventExchange(event)).block(Duration.ofSeconds(5))

        sentBodyTypes.assert().containsSequence(MockCreateAggregate::class.java, MockChangeAggregate::class.java)
    }

    @Test
    fun `should send returned command message when saga returns command message`() {
        val returnedCommand = MockCreateAggregate("", "").toCommandMessage()
        val sentCommands = mutableListOf<CommandMessage<*>>()
        val commandGateway = mockk<CommandGateway> {
            every { send(any<CommandMessage<*>>()) } answers {
                val sentCommand = firstArg<CommandMessage<*>>()
                sentCommand.withReadOnly()
                sentCommands.add(sentCommand)
                Mono.empty()
            }
        }
        val delegate = object : MessageFunction<Any, DomainEventExchange<*>, Mono<*>> {
            override val contextName: String = "context"
            override val name: String = "onEvent"
            override val processor: Any = "processor"
            override val supportedType: Class<*> = MockAggregateCreated::class.java
            override val supportedTopics = emptySet<me.ahoo.wow.api.modeling.NamedAggregate>()
            override val functionKind: FunctionKind = FunctionKind.EVENT
            override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = null
            override fun invoke(exchange: DomainEventExchange<*>): Mono<*> = Mono.just(returnedCommand)
        }
        val statelessSagaFunction = StatelessSagaFunction(delegate, commandGateway, mockk())
        val upstream = MockCreateAggregate(generateGlobalId(), "data").toCommandMessage()
        val event = MockAggregateCreated("data").toDomainEventStream(
            upstream = upstream,
            aggregateVersion = 1,
        ).body.first()

        statelessSagaFunction.invoke(SimpleDomainEventExchange(event)).block(Duration.ofSeconds(5))

        sentCommands.assert().hasSize(1)
        sentCommands.first().assert().isSameAs(returnedCommand)
        returnedCommand.isReadOnly.assert().isTrue()
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
