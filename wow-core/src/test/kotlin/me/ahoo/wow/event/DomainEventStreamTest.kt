package me.ahoo.wow.event

import me.ahoo.test.asserts.assert
import me.ahoo.wow.id.generateGlobalId
import org.junit.jupiter.api.Test

class DomainEventStreamTest {

    @Test
    fun ignoreSourcingIfNotInitialVersion() {
        val eventStream = MockNamedEvent().toDomainEvent(
            aggregateId = generateGlobalId(),
            tenantId = generateGlobalId(),
            commandId = generateGlobalId(),
            version = 2
        ).let {
            SimpleDomainEventStream(requestId = generateGlobalId(), body = listOf(it))
        }

        val ignoreSourcing = eventStream.ignoreSourcing()
        ignoreSourcing.assert().isFalse()
    }

    @Test
    fun ignoreSourcing() {
        val eventStream = MockNamedEvent().toDomainEvent(
            aggregateId = generateGlobalId(),
            tenantId = generateGlobalId(),
            commandId = generateGlobalId(),
            version = 1
        ).let {
            SimpleDomainEventStream(requestId = generateGlobalId(), body = listOf(it))
        }
        val ignoreSourcing = eventStream.ignoreSourcing()
        ignoreSourcing.assert().isFalse()
    }

    @Test
    fun ignoreSourcingIfErrorEvent() {
        val eventStream = ErrorIgnoreEvent(
            errorCode = generateGlobalId(),
            errorMsg = generateGlobalId()
        ).toDomainEvent(
            aggregateId = generateGlobalId(),
            tenantId = generateGlobalId(),
            commandId = generateGlobalId(),
            version = 1
        ).let {
            SimpleDomainEventStream(requestId = generateGlobalId(), body = listOf(it))
        }
        val ignoreSourcing = eventStream.ignoreSourcing()
        ignoreSourcing.assert().isTrue()
    }
}
