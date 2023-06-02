package me.ahoo.wow.eventsourcing.snapshot

import io.mockk.mockk
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.SimpleEventStreamExchange
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class ExchangeSnapshotKtTest {

    @Test
    fun setSnapshot() {
        val exchange = SimpleEventStreamExchange(mockk<DomainEventStream>())
        assertThat(exchange.setSnapshot(mockk()).getSnapshot<Any>(), notNullValue())
    }
}
