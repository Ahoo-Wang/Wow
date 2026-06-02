package me.ahoo.wow.event

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.MockCreateCommand
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.messaging.DefaultHeader
import org.junit.jupiter.api.Test

class DomainEventStreamFactoryKtTest {

    @Test
    fun `should flat event list`() {
        val flat = listOf(1, 2).flatEvent().toList()
        flat.size.assert().isEqualTo(2)
    }

    @Test
    fun `should flat event array`() {
        val flat = arrayOf(1, 2).flatEvent().toList()
        flat.size.assert().isEqualTo(2)
    }

    @Test
    fun `should flat event other`() {
        val flat = Any().flatEvent().toList()
        flat.size.assert().isEqualTo(1)
    }

    @Test
    fun `should create event stream from one shot iterable`() {
        val events = OneShotEvents(listOf(MockNamedEvent(), MockNamedEvent()))

        val eventStream = events.toDomainEventStream(MockCreateCommand("id").toCommandMessage())

        eventStream.body.assert().hasSize(2)
    }

    @Test
    fun `should create event stream with supplied header`() {
        val header = DefaultHeader.empty().with("source", "original")

        val eventStream = MockNamedEvent().toDomainEventStream(
            upstream = MockCreateCommand("id").toCommandMessage(),
            header = header,
        )
        header["source"] = "mutated"

        eventStream.header.assert().isSameAs(header)
        eventStream.first().header.assert().isSameAs(header)
        eventStream.header["source"].assert().isEqualTo("mutated")
        eventStream.first().header["source"].assert().isEqualTo("mutated")
    }

    private class OneShotEvents(
        private val events: List<Any>
    ) : Iterable<Any> {
        private var consumed = false

        override fun iterator(): Iterator<Any> {
            if (consumed) {
                return emptyList<Any>().iterator()
            }
            consumed = true
            return events.iterator()
        }
    }
}
