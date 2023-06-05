package me.ahoo.wow.opentelemetry.messaging

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.opentelemetry.ExchangeTraceMonoTest.Companion.TEST_NAMED_AGGREGATE
import me.ahoo.wow.opentelemetry.Tracing.tracing
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class TracingLocalCommandBusTest {

    @Test
    fun send() {
        val commandMessage = mockk<CommandMessage<*>> {
            every { id } returns GlobalIdGenerator.generateAsString()
            every { requestId } returns GlobalIdGenerator.generateAsString()
            every { header } returns DefaultHeader.empty()
            every { isReadOnly } returns false
            every { withReadOnly() } returns this
            every { contextName } returns TEST_NAMED_AGGREGATE.contextName
            every { aggregateName } returns TEST_NAMED_AGGREGATE.aggregateName
            every { aggregateId } returns TEST_NAMED_AGGREGATE.asAggregateId()
            every { name } returns "test"
        }
        InMemoryCommandBus().tracing().use {
            it.send(commandMessage)
                .test()
                .verifyComplete()
        }
    }
}
