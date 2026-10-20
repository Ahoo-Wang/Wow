package me.ahoo.wow.compensation.core

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.Retry
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.compensation.api.ApplyExecutionFailed
import me.ahoo.wow.compensation.api.ApplyExecutionSuccess
import me.ahoo.wow.compensation.api.CreateExecutionFailed
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.dispatcher.DefaultDomainEventHandler
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.compensation.COMPENSATION_ID
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.handler.isAcknowledgementWithheld
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.processing.failure.ProcessingOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import reactor.util.retry.Retry as ReactorRetry

private const val RETRIES = 3L

class CompensationFailureRecorderTest {
    private val handledErrors = CopyOnWriteArrayList<Throwable>()
    private val errorHandler = ErrorHandler<DomainEventExchange<*>> { _, error ->
        handledErrors.add(error)
        Mono.empty()
    }

    @BeforeEach
    fun clearHandledErrors() {
        handledErrors.clear()
    }

    @Test
    fun `the first failure of a function creates an execution`() {
        val sent = CopyOnWriteArrayList<CommandMessage<*>>()
        val recorder = CompensationFailureRecorder(capturingBus(sent))
        val exchange = exchange()
        val error = IllegalStateException("handler failed")

        recorder.recordFailure(exchange, error)
            .test()
            .expectNext(ProcessingOutcome.FAILURE_RECORDED)
            .verifyComplete()

        val body = sent.single().body as CreateExecutionFailed
        body.eventId.id.assert().isEqualTo(exchange.message.id)
        body.function.name.assert().isEqualTo("name")
        body.error.errorMsg.assert().isEqualTo("handler failed")
    }

    @Test
    fun `a failed compensation execution applies the failure to that execution`() {
        val sent = CopyOnWriteArrayList<CommandMessage<*>>()
        val executionId = GlobalIdGenerator.generateAsString()
        val recorder = CompensationFailureRecorder(capturingBus(sent))

        recorder.recordFailure(exchange(compensationId = executionId), IllegalStateException())
            .test()
            .expectNext(ProcessingOutcome.FAILURE_RECORDED)
            .verifyComplete()

        (sent.single().body as ApplyExecutionFailed).id.assert().isEqualTo(executionId)
    }

    @Test
    fun `a function that disables retries is not recorded`() {
        val commandBus = mockk<CommandBus>()
        val recorder = CompensationFailureRecorder(commandBus)

        recorder.recordFailure(exchange(retry = Retry(false)), IllegalStateException())
            .test()
            .expectNext(ProcessingOutcome.FAILURE_WAIVED)
            .verifyComplete()

        verify(exactly = 0) { commandBus.send(any()) }
    }

    @Test
    fun `an exchange without a function is unrecorded`() {
        val commandBus = mockk<CommandBus>()
        val exchange = mockk<DomainEventExchange<*>> {
            every { getFunction() } returns null
        }

        CompensationFailureRecorder(commandBus).recordFailure(exchange, IllegalStateException())
            .test()
            .expectNext(ProcessingOutcome.FAILURE_UNRECORDED)
            .verifyComplete()
    }

    @Test
    fun `a succeeded compensation execution is applied as a success`() {
        val sent = CopyOnWriteArrayList<CommandMessage<*>>()
        val executionId = GlobalIdGenerator.generateAsString()

        CompensationFailureRecorder(capturingBus(sent)).recordSuccess(exchange(compensationId = executionId))
            .test()
            .verifyComplete()

        (sent.single().body as ApplyExecutionSuccess).id.assert().isEqualTo(executionId)
    }

    @Test
    fun `a success outside a compensation execution sends nothing`() {
        val commandBus = mockk<CommandBus>()

        CompensationFailureRecorder(commandBus).recordSuccess(exchange())
            .test()
            .verifyComplete()

        verify(exactly = 0) { commandBus.send(any()) }
    }

    @Test
    fun `the handler keeps the handler error and withholds acknowledgement when recording fails`() {
        val recordFailure = IllegalStateException("command bus unavailable")
        val commandBus = mockk<CommandBus> {
            every { send(any()) } returns Mono.error(recordFailure)
        }
        val exchange = exchange()
        val error = IllegalArgumentException("handler failed")

        handler(commandBus, failing(exchange, error)).handle(exchange)
            .test()
            .verifyComplete()

        handledErrors.single().assert().isSameAs(error)
        error.suppressed.toList().assert().containsExactly(recordFailure)
        exchange.isAcknowledgementWithheld().assert().isTrue()
        verify(exactly = 1 + RETRIES.toInt()) { commandBus.send(any()) }
    }

    @Test
    fun `the handler acknowledges when a retried send records the failure`() {
        val attempts = AtomicInteger()
        val commandBus = mockk<CommandBus> {
            every { send(any()) } returns Mono.defer {
                if (attempts.incrementAndGet() < 3) {
                    Mono.error(IllegalStateException("transient"))
                } else {
                    Mono.empty()
                }
            }
        }
        val exchange = exchange()
        val error = IllegalArgumentException("handler failed")

        handler(commandBus, failing(exchange, error)).handle(exchange)
            .test()
            .verifyComplete()

        handledErrors.single().assert().isSameAs(error)
        error.suppressed.assert().isEmpty()
        exchange.isAcknowledgementWithheld().assert().isFalse()
        attempts.get().assert().isEqualTo(3)
    }

    @Test
    fun `the handler acknowledges a recorded failure even with ack-on-unrecorded-failure off`() {
        val exchange = exchange()
        val handler = DefaultDomainEventHandler(
            chain = failing(exchange, IllegalArgumentException("handler failed")),
            errorHandler = errorHandler,
            failureRecorder = CompensationFailureRecorder(capturingBus(CopyOnWriteArrayList())),
            ackOnUnrecordedFailure = false,
        )

        handler.handle(exchange).test().verifyComplete()

        exchange.isAcknowledgementWithheld().assert().isFalse()
    }

    @Test
    fun `a shared handler error does not accumulate suppressed record failures across redeliveries`() {
        val commandBus = mockk<CommandBus> {
            every { send(any()) } answers { Mono.error(IllegalStateException("command bus unavailable")) }
        }
        val sharedError = IllegalArgumentException("handler failed")

        repeat(3) {
            val exchange = exchange()
            handler(commandBus, failing(exchange, sharedError)).handle(exchange)
                .test()
                .verifyComplete()
        }

        sharedError.suppressed.assert().hasSize(1)
    }

    @Test
    fun `a failed success record goes to the error handler`() {
        val sendFailure = IllegalStateException("command bus unavailable")
        val commandBus = mockk<CommandBus> {
            every { send(any()) } returns Mono.error(sendFailure)
        }
        val exchange = exchange(compensationId = GlobalIdGenerator.generateAsString())
        val chain = mockk<FilterChain<DomainEventExchange<*>>> {
            every { filter(exchange) } returns Mono.empty()
        }

        handler(commandBus, chain).handle(exchange)
            .test()
            .verifyComplete()

        handledErrors.single().assert().isSameAs(sendFailure)
        exchange.isAcknowledgementWithheld().assert().isFalse()
    }

    @Test
    fun `should return null retry for unknown function`() {
        val functionInfo = FunctionInfoData.unknown(FunctionKind.EVENT, "contextName")
        functionInfo.getRetry().assert().isNull()
    }

    private fun handler(commandBus: CommandBus, chain: FilterChain<DomainEventExchange<*>>) =
        DefaultDomainEventHandler(
            chain = chain,
            errorHandler = errorHandler,
            failureRecorder = CompensationFailureRecorder(
                commandBus = commandBus,
                recordFailureRetry = ReactorRetry.backoff(RETRIES, Duration.ofMillis(1)),
            ),
        )

    private fun failing(exchange: DomainEventExchange<*>, error: Throwable): FilterChain<DomainEventExchange<*>> =
        mockk {
            every { filter(exchange) } returns error.toMono()
        }

    private fun capturingBus(sent: MutableList<CommandMessage<*>>): CommandBus =
        mockk {
            every { send(any()) } answers {
                sent.add(firstArg())
                Mono.empty()
            }
        }

    private fun exchange(compensationId: String? = null, retry: Retry = Retry()): DomainEventExchange<*> {
        val eventFunction = mockk<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>> {
            every { functionKind } returns FunctionKind.EVENT
            every { contextName } returns "contextName"
            every { processorName } returns "processorName"
            every { name } returns "name"
            every { getAnnotation(Retry::class.java) } returns retry
        }
        var header = DefaultHeader.empty()
        if (compensationId != null) {
            header = header.with(COMPENSATION_ID, compensationId)
        }
        val attributes = ConcurrentHashMap<String, Any>()
        val eventId = GlobalIdGenerator.generateAsString()
        return mockk<DomainEventExchange<*>> {
            every { message.id } returns eventId
            every { message.aggregateId } returns "test.test".toNamedAggregate().aggregateId()
            every { message.version } returns 1
            every { message.header } returns header
            every { getFunction() } returns eventFunction
            every { this@mockk.attributes } returns attributes
            every { setError(any()) } just runs
        }
    }
}
