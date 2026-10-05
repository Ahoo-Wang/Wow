package me.ahoo.wow.compensation.core

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.Retry
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.dispatcher.DefaultDomainEventHandler
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.compensation.COMPENSATION_ID
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.serialization.toJsonString
import org.junit.jupiter.api.Test
import reactor.core.Exceptions
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The compensation commands sent for a failed (or recovered) event function are frozen wire: their bodies must stay
 * byte-identical to the 9.2 `EventCompensationFilter` output. The golden files were captured from that filter; the
 * event handler with [CompensationFailureRecorder] must reproduce them. Only `executeAt`, the send time, is
 * normalised.
 */
class ExecutionFailedWireGoldenTest {

    @Test
    fun `CreateExecutionFailed body is byte-identical to 9_2`() {
        val sent = record(compensationId = null, error = handlerError())

        sent.assertGolden("create-execution-failed.json")
    }

    @Test
    fun `a failure whose in-process retries were exhausted is recorded as its cause`() {
        val exhausted = Exceptions.retryExhausted("Retries exhausted: 3/3", handlerError())

        val sent = record(compensationId = null, error = exhausted)

        // Since 9.3.0 the record is the cause's; 9.2 recorded the retry-exhausted wrapper (IllegalState).
        sent.assertGolden("create-execution-failed.json")
    }

    @Test
    fun `ApplyExecutionFailed body is byte-identical to 9_2`() {
        val sent = record(compensationId = EXECUTION_ID, error = handlerError())

        sent.assertGolden("apply-execution-failed.json")
    }

    @Test
    fun `ApplyExecutionSuccess body is byte-identical to 9_2`() {
        val sent = record(compensationId = EXECUTION_ID, error = null)

        sent.assertGolden("apply-execution-success.json")
    }

    private fun record(compensationId: String?, error: Throwable?): CommandMessage<*> {
        val sent = mutableListOf<CommandMessage<*>>()
        val commandBus = mockk<CommandBus> {
            every { send(any()) } answers {
                sent.add(firstArg())
                Mono.empty()
            }
        }
        val exchange = exchange(compensationId)
        val next: FilterChain<DomainEventExchange<*>> = mockk {
            every { filter(exchange) } returns (error?.toMono() ?: Mono.empty())
        }
        DefaultDomainEventHandler(chain = next, failureRecorder = CompensationFailureRecorder(commandBus))
            .handle(exchange)
            .block()
        return sent.single()
    }

    private fun exchange(compensationId: String?): DomainEventExchange<*> {
        val eventFunction = mockk<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>> {
            every { functionKind } returns FunctionKind.EVENT
            every { contextName } returns "order-service"
            every { processorName } returns "OrderProjector"
            every { name } returns "onOrderCreated"
            every { getAnnotation(Retry::class.java) } returns Retry(true, 5, 30, 60)
        }
        var header = DefaultHeader.empty()
        if (compensationId != null) {
            header = header.with(COMPENSATION_ID, compensationId)
        }
        val attributes = ConcurrentHashMap<String, Any>()
        return mockk<DomainEventExchange<*>> {
            every { message.id } returns "0TrEvent00001"
            every { message.aggregateId } returns "order-service.order".toNamedAggregate().aggregateId("0TrOrder00001")
            every { message.version } returns 3
            every { message.header } returns header
            every { getFunction() } returns eventFunction
            every { this@mockk.attributes } returns attributes
            every { setError(any()) } just runs
        }
    }

    private fun handlerError(): Throwable =
        IllegalStateException("projection store rejected the write").apply {
            stackTrace = arrayOf(StackTraceElement("me.ahoo.example.OrderProjector", "onOrderCreated", "OrderProjector.kt", 42))
        }

    private fun CommandMessage<*>.assertGolden(name: String) {
        val actual = body.toJsonString().replace(EXECUTE_AT, "\"executeAt\":0")
        val golden = File(GOLDEN_DIR, name)
        if (!golden.exists()) {
            golden.parentFile.mkdirs()
            golden.writeText(actual)
            error("Golden file $name was missing and has been written; review and commit it.")
        }
        actual.assert().isEqualTo(golden.readText())
    }

    private companion object {
        const val EXECUTION_ID = "0TrExecution01"
        const val GOLDEN_DIR = "src/test/resources/golden/compensation"
        val EXECUTE_AT = Regex("\"executeAt\":\\d+")
    }
}
