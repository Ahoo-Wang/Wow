package me.ahoo.wow.compensation.core

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.Retry
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.messaging.compensation.COMPENSATION_ID
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.handler.isAcknowledgementWithheld
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.modeling.toNamedAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import reactor.util.retry.Retry as ReactorRetry

private const val RETRIES = 3L

class DomainEventCompensationFilterTest {

    @Test
    fun `should pass through when no execution id`() {
        val commandBus = InMemoryCommandBus()
        val compensationFilter = DomainEventCompensationFilter(commandBus)
        val exchange = mockk<DomainEventExchange<*>> {
            every { message.header } returns DefaultHeader.empty()
        }

        val next: FilterChain<DomainEventExchange<*>> = mockk {
            every { filter(exchange) } returns Mono.empty()
        }
        compensationFilter.filter(exchange, next)
            .test()
            .verifyComplete()
    }

    @Test
    fun `should pass through when execution id is present`() {
        val commandBus = InMemoryCommandBus()
        val compensationFilter = DomainEventCompensationFilter(commandBus)
        val exchange = mockk<DomainEventExchange<*>> {
            every { message.header } returns DefaultHeader.empty()
                .with(COMPENSATION_ID, GlobalIdGenerator.generateAsString())
        }

        val next: FilterChain<DomainEventExchange<*>> = mockk {
            every { filter(exchange) } returns Mono.empty()
        }
        compensationFilter.filter(exchange, next)
            .test()
            .verifyComplete()
    }

    @Test
    fun `should propagate error when event function is null`() {
        val commandBus = InMemoryCommandBus()
        val compensationFilter = DomainEventCompensationFilter(commandBus)
        val exchange = mockk<DomainEventExchange<*>> {
            every { message.header } returns DefaultHeader.empty()
            every { getFunction() } returns null
        }
        val error = IllegalStateException()
        val next: FilterChain<DomainEventExchange<*>> = mockk {
            every { filter(exchange) } returns error.toMono()
        }
        compensationFilter.filter(exchange, next)
            .test()
            .expectErrorMatches { it === error }
            .verify()
    }

    @Test
    fun `should propagate error from filter chain`() {
        val commandBus = InMemoryCommandBus()
        val compensationFilter = DomainEventCompensationFilter(commandBus)
        val eventFunction = mockk<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>> {
            every { functionKind } returns FunctionKind.EVENT
            every { contextName } returns "contextName"
            every { processorName } returns "processorName"
            every { name } returns "name"
            every { getAnnotation(Retry::class.java) } returns Retry(true, 1, 1, 1)
        }
        val exchange = mockk<DomainEventExchange<*>> {
            every { message.id } returns GlobalIdGenerator.generateAsString()
            every { message.aggregateId } returns "test.test".toNamedAggregate().aggregateId()
            every { message.version } returns 1
            every { message.header } returns DefaultHeader.empty()
            every { getFunction() } returns eventFunction
        }
        val error = IllegalStateException()
        val next: FilterChain<DomainEventExchange<*>> = mockk {
            every { filter(exchange) } returns error.toMono()
        }
        compensationFilter.filter(exchange, next)
            .test()
            .expectErrorMatches { it === error }
            .verify()
    }

    @Test
    fun `should send compensation command on error with execution id`() {
        val commandBus = InMemoryCommandBus()
        val compensationFilter = DomainEventCompensationFilter(commandBus)
        val sink = Sinks.empty<Void>()
        commandBus.receive(MessageSubscription(CompensationEventProcessorTest.LOCAL_AGGREGATE.materialize()))
            .doOnNext {
                sink.tryEmitEmpty()
            }
            .subscribe()
        val eventFunction = mockk<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>> {
            every { functionKind } returns FunctionKind.EVENT
            every { contextName } returns "contextName"
            every { processorName } returns "processorName"
            every { getAnnotation(Retry::class.java) } returns Retry()
        }
        val exchange = mockk<DomainEventExchange<*>> {
            every { message.id } returns GlobalIdGenerator.generateAsString()
            every { message.aggregateId } returns "test.test".toNamedAggregate().aggregateId()
            every { message.version } returns 1
            every { message.header } returns DefaultHeader.empty()
                .with(COMPENSATION_ID, GlobalIdGenerator.generateAsString())
            every { getFunction() } returns eventFunction
        }
        val error = IllegalStateException()
        val next: FilterChain<DomainEventExchange<*>> = mockk {
            every { filter(exchange) } returns error.toMono()
        }
        compensationFilter.filter(exchange, next)
            .test()
            .expectErrorMatches { it === error }
            .verify()
        sink.asMono()
            .test()
            .verifyComplete()
    }

    @Test
    fun `should keep the handler error and withhold acknowledgement when recording the failure fails`() {
        val recordFailure = IllegalStateException("command bus unavailable")
        val commandBus = mockk<CommandBus> {
            every { send(any()) } returns Mono.error(recordFailure)
        }
        val compensationFilter = DomainEventCompensationFilter(commandBus).fastRetry()
        val exchange = failedFunctionExchange()
        val error = IllegalArgumentException("handler failed")
        val next: FilterChain<DomainEventExchange<*>> = mockk {
            every { filter(exchange) } returns error.toMono()
        }

        compensationFilter.filter(exchange, next)
            .test()
            .expectErrorSatisfies {
                it.assert().isSameAs(error)
                it.suppressed.toList().assert().containsExactly(recordFailure)
            }
            .verify()
        exchange.isAcknowledgementWithheld().assert().isTrue()
        verify(exactly = 1 + RETRIES.toInt()) { commandBus.send(any()) }
    }

    @Test
    fun `should not withhold acknowledgement when a retried send records the failure`() {
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
        val compensationFilter = DomainEventCompensationFilter(commandBus).fastRetry()
        val exchange = failedFunctionExchange()
        val error = IllegalArgumentException("handler failed")
        val next: FilterChain<DomainEventExchange<*>> = mockk {
            every { filter(exchange) } returns error.toMono()
        }

        compensationFilter.filter(exchange, next)
            .test()
            .expectErrorSatisfies {
                it.assert().isSameAs(error)
                it.suppressed.assert().isEmpty()
            }
            .verify()
        exchange.isAcknowledgementWithheld().assert().isFalse()
        attempts.get().assert().isEqualTo(3)
    }

    @Test
    fun `a shared handler error does not accumulate suppressed record failures across redeliveries`() {
        val commandBus = mockk<CommandBus> {
            every { send(any()) } answers { Mono.error(IllegalStateException("command bus unavailable")) }
        }
        val compensationFilter = DomainEventCompensationFilter(commandBus).fastRetry()
        val sharedError = IllegalArgumentException("handler failed")

        repeat(3) {
            val exchange = failedFunctionExchange()
            val next: FilterChain<DomainEventExchange<*>> = mockk {
                every { filter(exchange) } returns sharedError.toMono()
            }
            compensationFilter.filter(exchange, next)
                .test()
                .expectErrorMatches { it === sharedError }
                .verify()
        }
        sharedError.suppressed.assert().hasSize(1)
    }

    @Test
    fun `the handler error is never suppressed in itself`() {
        val error = IllegalArgumentException("handler failed")

        error.addSuppressedOnce(error)

        error.suppressed.assert().isEmpty()
    }

    private fun DomainEventCompensationFilter.fastRetry(): DomainEventCompensationFilter = apply {
        recordFailureRetry = ReactorRetry.backoff(RETRIES, Duration.ofMillis(1))
    }

    @Test
    fun `should not withhold acknowledgement when the failure is recorded`() {
        val commandBus = mockk<CommandBus> {
            every { send(any()) } returns Mono.empty()
        }
        val compensationFilter = DomainEventCompensationFilter(commandBus)
        val exchange = failedFunctionExchange()
        val error = IllegalArgumentException("handler failed")
        val next: FilterChain<DomainEventExchange<*>> = mockk {
            every { filter(exchange) } returns error.toMono()
        }

        compensationFilter.filter(exchange, next)
            .test()
            .expectErrorSatisfies {
                it.assert().isSameAs(error)
                it.suppressed.assert().isEmpty()
            }
            .verify()
        exchange.isAcknowledgementWithheld().assert().isFalse()
    }

    private fun failedFunctionExchange(): DomainEventExchange<*> {
        val eventFunction = mockk<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>> {
            every { functionKind } returns FunctionKind.EVENT
            every { contextName } returns "contextName"
            every { processorName } returns "processorName"
            every { name } returns "name"
            every { getAnnotation(Retry::class.java) } returns Retry()
        }
        val attributes = ConcurrentHashMap<String, Any>()
        return mockk<DomainEventExchange<*>> {
            every { message.id } returns GlobalIdGenerator.generateAsString()
            every { message.aggregateId } returns "test.test".toNamedAggregate().aggregateId()
            every { message.version } returns 1
            every { message.header } returns DefaultHeader.empty()
            every { getFunction() } returns eventFunction
            every { this@mockk.attributes } returns attributes
        }
    }

    @Test
    fun `should not retry when retry is disabled`() {
        val commandBus = InMemoryCommandBus()
        val sink = Sinks.empty<Void>()
        commandBus.receive(MessageSubscription(CompensationEventProcessorTest.LOCAL_AGGREGATE.materialize()))
            .doOnNext {
                sink.tryEmitEmpty()
            }
            .subscribe()
        val compensationFilter = DomainEventCompensationFilter(commandBus)
        val eventFunction = mockk<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>> {
            every { functionKind } returns FunctionKind.EVENT
            every { contextName } returns "contextName"
            every { processorName } returns "processorName"
            every { getAnnotation(Retry::class.java) } returns Retry(false)
        }
        val exchange = mockk<DomainEventExchange<*>> {
            every { message.id } returns GlobalIdGenerator.generateAsString()
            every { message.aggregateId } returns "test.test".toNamedAggregate().aggregateId()
            every { message.version } returns 1
            every { message.header } returns DefaultHeader.empty()
                .with(COMPENSATION_ID, GlobalIdGenerator.generateAsString())
            every { getFunction() } returns eventFunction
        }
        val error = IllegalStateException()
        val next: FilterChain<DomainEventExchange<*>> = mockk {
            every { filter(exchange) } returns error.toMono()
        }
        compensationFilter.filter(exchange, next)
            .test()
            .expectErrorMatches { it === error }
            .verify()
        sink.asMono()
            .test()
            .expectTimeout(Duration.ofSeconds(1))
            .verify()
    }

    @Test
    fun `should return null retry for unknown function`() {
        val functionInfo = FunctionInfoData.unknown(FunctionKind.EVENT, "contextName")
        functionInfo.getRetry().assert().isNull()
    }
}
