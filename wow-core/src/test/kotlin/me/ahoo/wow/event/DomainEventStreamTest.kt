package me.ahoo.wow.event

import me.ahoo.test.asserts.assert
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.serialization.event.JsonDomainEvent
import me.ahoo.wow.serialization.toJsonNode
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

class DomainEventStreamTest {

    @Test
    fun `should ignore sourcing if not initial version`() {
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
    fun `should ignore sourcing`() {
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
    fun `should ignore sourcing if error event`() {
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

    @Test
    fun `should copy json domain event header`() {
        val sourceEvent = MockNamedEvent().toDomainEvent(
            aggregateId = generateGlobalId(),
            tenantId = generateGlobalId(),
            commandId = generateGlobalId(),
            version = 1
        )
        val jsonDomainEvent = JsonDomainEvent(
            id = sourceEvent.id,
            header = DefaultHeader.empty().with("source", "original"),
            bodyType = "UnknownEvent",
            body = """{"data":"value"}""".toJsonNode<ObjectNode>(),
            aggregateId = sourceEvent.aggregateId,
            ownerId = sourceEvent.ownerId,
            spaceId = sourceEvent.spaceId,
            version = sourceEvent.version,
            sequence = sourceEvent.sequence,
            revision = sourceEvent.revision,
            commandId = sourceEvent.commandId,
            name = sourceEvent.name,
            isLast = sourceEvent.isLast,
            createTime = sourceEvent.createTime
        )
        val eventStream = SimpleDomainEventStream(
            requestId = generateGlobalId(),
            body = listOf(jsonDomainEvent)
        )

        val copiedEvent = eventStream.copy().first() as JsonDomainEvent
        copiedEvent.header["source"] = "copied"

        jsonDomainEvent.header["source"].assert().isEqualTo("original")
    }
}
