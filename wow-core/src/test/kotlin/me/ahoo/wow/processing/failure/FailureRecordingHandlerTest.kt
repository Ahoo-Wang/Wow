/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.processing.failure

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.EventExchange
import me.ahoo.wow.event.dispatcher.DefaultDomainEventHandler
import me.ahoo.wow.eventsourcing.snapshot.dispatcher.DefaultSnapshotHandler
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.LogErrorHandler
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.handler.isAcknowledgementWithheld
import me.ahoo.wow.projection.DefaultProjectionHandler
import me.ahoo.wow.saga.stateless.DefaultStatelessSagaHandler
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.Exceptions
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class FailureRecordingHandlerTest {
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
    fun `a handled event is acknowledged and the recorder hears of the success`() {
        val exchange = exchange()
        val successes = CopyOnWriteArrayList<EventExchange<*, *>>()
        val recorder = recorder(onSuccess = { successes.add(it) })

        handler(succeeding(exchange), recorder).handle(exchange).test().verifyComplete()

        successes.single().assert().isSameAs(exchange)
        handledErrors.assert().isEmpty()
        exchange.isAcknowledgementWithheld().assert().isFalse()
    }

    @Test
    fun `by default an unrecorded failure is logged and acknowledged as before 9_3`() {
        val exchange = exchange()
        val error = IllegalStateException("handler failed")

        DefaultDomainEventHandler(failing(exchange, error)).handle(exchange).test().verifyComplete()

        exchange.isAcknowledgementWithheld().assert().isFalse()
        exchange.getError().assert().isSameAs(error)
    }

    @Test
    fun `every default handler acknowledges an unrecorded failure`() {
        listOf<(FilterChain<DomainEventExchange<*>>) -> FailureRecordingHandler<DomainEventExchange<*>>>(
            { DefaultDomainEventHandler(it) },
            { DefaultProjectionHandler(it) },
            { DefaultStatelessSagaHandler(it) },
        ).forEach { create ->
            val exchange = exchange()

            create(failing(exchange, IllegalStateException("handler failed"))).handle(exchange).test().verifyComplete()

            exchange.isAcknowledgementWithheld().assert().isFalse()
        }
        val stateExchange = mockk<StateEventExchange<*>>(relaxed = true)
        val stateChain = mockk<FilterChain<StateEventExchange<*>>> {
            every { filter(stateExchange) } returns Mono.error(IllegalStateException("snapshot"))
        }
        DefaultSnapshotHandler(stateChain).handle(stateExchange).test().verifyComplete()
    }

    @Test
    fun `an unrecorded failure is not acknowledged when ack-on-unrecorded-failure is off`() {
        val exchange = exchange()
        val error = IllegalStateException("handler failed")

        handler(failing(exchange, error), FailureRecorder.NONE, ackOnUnrecordedFailure = false)
            .handle(exchange).test().verifyComplete()

        exchange.isAcknowledgementWithheld().assert().isTrue()
        handledErrors.single().assert().isSameAs(error)
    }

    @Test
    fun `a recorder that emits nothing leaves the failure unrecorded`() {
        val exchange = exchange()

        handler(failing(exchange, IllegalStateException()), recorder { Mono.empty() }, ackOnUnrecordedFailure = false)
            .handle(exchange).test().verifyComplete()

        exchange.isAcknowledgementWithheld().assert().isTrue()
    }

    @Test
    fun `a recorded or waived failure is acknowledged even with ack-on-unrecorded-failure off`() {
        listOf(ProcessingOutcome.FAILURE_RECORDED, ProcessingOutcome.FAILURE_WAIVED).forEach { outcome ->
            val exchange = exchange()

            handler(failing(exchange, IllegalStateException()), recorder { Mono.just(outcome) }, false)
                .handle(exchange).test().verifyComplete()

            exchange.isAcknowledgementWithheld().assert().isFalse()
        }
    }

    @Test
    fun `a failed recording is never acknowledged and its error is suppressed in the processing error`() {
        val exchange = exchange()
        val error = IllegalStateException("handler failed")
        val recordError = IllegalStateException("recorder down")

        handler(failing(exchange, error), recorder { Mono.error(recordError) })
            .handle(exchange).test().verifyComplete()

        exchange.isAcknowledgementWithheld().assert().isTrue()
        handledErrors.single().assert().isSameAs(error)
        error.suppressed.toList().assert().containsExactly(recordError)
    }

    @Test
    fun `an exhausted recording retry suppresses its cause`() {
        val exchange = exchange()
        val error = IllegalStateException("handler failed")
        val recordError = IllegalStateException("recorder down")

        handler(failing(exchange, error), recorder { Mono.error(Exceptions.retryExhausted("exhausted", recordError)) })
            .handle(exchange).test().verifyComplete()

        error.suppressed.toList().assert().containsExactly(recordError)
    }

    @Test
    fun `a failed success record goes to the error handler and is acknowledged`() {
        val exchange = exchange()
        val successError = IllegalStateException("success record failed")

        handler(succeeding(exchange), recorder(onSuccess = { throw successError }))
            .handle(exchange).test().verifyComplete()

        handledErrors.single().assert().isSameAs(successError)
        exchange.isAcknowledgementWithheld().assert().isFalse()
    }

    @Test
    fun `an error handler that rethrows still propagates the processing error`() {
        val exchange = exchange()
        val error = IllegalStateException("handler failed")

        DefaultProjectionHandler(failing(exchange, error), LogErrorHandler())
            .handle(exchange)
            .test()
            .expectErrorMatches { it === error }
            .verify()
    }

    @Test
    fun `saga and snapshot handlers record failures too`() {
        val exchange = exchange()
        val recorded = CopyOnWriteArrayList<Throwable>()
        val recorder = recorder { error ->
            recorded.add(error)
            Mono.just(ProcessingOutcome.FAILURE_RECORDED)
        }
        DefaultStatelessSagaHandler(failing(exchange, IllegalStateException("saga")), errorHandler, recorder)
            .handle(exchange).test().verifyComplete()
        val stateExchange = mockk<StateEventExchange<*>>(relaxed = true)
        val stateChain = mockk<FilterChain<StateEventExchange<*>>> {
            every { filter(stateExchange) } returns Mono.error(IllegalStateException("snapshot"))
        }
        DefaultSnapshotHandler(stateChain, { _, _ -> Mono.empty() }, recorder)
            .handle(stateExchange).test().verifyComplete()

        recorded.map { it.message }.assert().containsExactly("saga", "snapshot")
    }

    @Test
    fun `outcomes acknowledge according to the switch`() {
        ProcessingOutcome.HANDLED.acknowledges(false).assert().isTrue()
        ProcessingOutcome.FAILURE_RECORDED.acknowledges(false).assert().isTrue()
        ProcessingOutcome.FAILURE_WAIVED.acknowledges(false).assert().isTrue()
        ProcessingOutcome.FAILURE_UNRECORDED.acknowledges(true).assert().isTrue()
        ProcessingOutcome.FAILURE_UNRECORDED.acknowledges(false).assert().isFalse()
        ProcessingOutcome.RECORDING_FAILED.acknowledges(true).assert().isFalse()
        FailureRecorder.NONE.toString().assert().isEqualTo("FailureRecorder.NONE")
    }

    @Test
    fun `an error is never suppressed in itself nor twice`() {
        val error = IllegalArgumentException("handler failed")

        error.addSuppressedOnce(error)
        error.addSuppressedOnce(IllegalStateException("down"))
        error.addSuppressedOnce(IllegalStateException("down"))

        error.suppressed.assert().hasSize(1)
    }

    private fun handler(
        chain: FilterChain<DomainEventExchange<*>>,
        recorder: FailureRecorder,
        ackOnUnrecordedFailure: Boolean = true,
    ) = DefaultDomainEventHandler(chain, errorHandler, recorder, ackOnUnrecordedFailure)

    private fun recorder(
        onSuccess: (EventExchange<*, *>) -> Unit = {},
        onFailure: (Throwable) -> Mono<ProcessingOutcome> = { Mono.just(ProcessingOutcome.FAILURE_RECORDED) },
    ): FailureRecorder = object : FailureRecorder {
        override fun recordFailure(exchange: EventExchange<*, *>, error: Throwable): Mono<ProcessingOutcome> =
            onFailure(error)

        override fun recordSuccess(exchange: EventExchange<*, *>): Mono<Void> =
            Mono.fromRunnable { onSuccess(exchange) }
    }

    private fun succeeding(exchange: DomainEventExchange<*>): FilterChain<DomainEventExchange<*>> =
        mockk { every { filter(exchange) } returns Mono.empty() }

    private fun failing(exchange: DomainEventExchange<*>, error: Throwable): FilterChain<DomainEventExchange<*>> =
        mockk { every { filter(exchange) } returns Mono.error(error) }

    private fun exchange(): DomainEventExchange<*> {
        val attributes = ConcurrentHashMap<String, Any>()
        val id = generateGlobalId()
        return mockk<DomainEventExchange<*>> {
            every { message.id } returns id
            every { this@mockk.attributes } returns attributes
            every { getFunction() } returns null
            every { setError(any()) } answers { attributes["__error__"] = firstArg() }
            every { getError() } answers { attributes["__error__"] as Throwable? }
        }
    }
}
