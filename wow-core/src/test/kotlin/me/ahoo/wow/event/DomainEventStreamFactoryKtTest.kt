package me.ahoo.wow.event

import me.ahoo.test.asserts.assert
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
}
