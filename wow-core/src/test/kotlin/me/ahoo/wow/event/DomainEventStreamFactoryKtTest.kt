package me.ahoo.wow.event

import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test

class DomainEventStreamFactoryKtTest {

    @Test
    fun flatEventList() {
        val flat = listOf(1, 2).flatEvent().toList()
        assertThat(flat.size, equalTo(2))
    }

    @Test
    fun flatEventArray() {
        val flat = arrayOf(1, 2).flatEvent().toList()
        assertThat(flat.size, equalTo(2))
    }

    @Test
    fun flatEventOther() {
        val flat = Any().flatEvent().toList()
        assertThat(flat.size, equalTo(1))
    }
}
