package me.ahoo.wow.event

import me.ahoo.wow.id.GlobalIdGenerator
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class DomainEventStreamTest {

    @Test
    fun ignoreSourcingIfNotInitialVersion() {
        val eventStream = MockNamedEvent().asDomainEvent(
            aggregateId = GlobalIdGenerator.generateAsString(),
            tenantId = GlobalIdGenerator.generateAsString(),
            commandId = GlobalIdGenerator.generateAsString(),
            version = 2
        ).let {
            SimpleDomainEventStream(requestId = GlobalIdGenerator.generateAsString(), body = listOf(it))
        }

        val ignoreSourcing = eventStream.ignoreSourcing()
        assertThat(ignoreSourcing, equalTo(false))
    }

    @Test
    fun ignoreSourcing() {
        val eventStream = MockNamedEvent().asDomainEvent(
            aggregateId = GlobalIdGenerator.generateAsString(),
            tenantId = GlobalIdGenerator.generateAsString(),
            commandId = GlobalIdGenerator.generateAsString(),
            version = 1
        ).let {
            SimpleDomainEventStream(requestId = GlobalIdGenerator.generateAsString(), body = listOf(it))
        }
        val ignoreSourcing = eventStream.ignoreSourcing()
        assertThat(ignoreSourcing, equalTo(false))
    }

    @Test
    fun ignoreSourcingIfErrorEvent() {
        val eventStream = ErrorIgnoreEvent(
            errorCode = GlobalIdGenerator.generateAsString(),
            errorMsg = GlobalIdGenerator.generateAsString()
        ).asDomainEvent(
            GlobalIdGenerator.generateAsString(),
            GlobalIdGenerator.generateAsString(),
            GlobalIdGenerator.generateAsString(),
            version = 1
        ).let {
            SimpleDomainEventStream(requestId = GlobalIdGenerator.generateAsString(), body = listOf(it))
        }
        val ignoreSourcing = eventStream.ignoreSourcing()
        assertThat(ignoreSourcing, equalTo(true))
    }
}
