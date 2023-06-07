package me.ahoo.wow.messaging.compensation

import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.compensation.CompensationMatcher.match
import me.ahoo.wow.messaging.compensation.CompensationMatcher.withCompensation
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class CompensationMatcherTest {

    @Test
    fun matchIfNull() {
        assertThat(DefaultHeader.empty().match(""), equalTo(true))
    }

    @Test
    fun matchIfEmpty() {
        val header = DefaultHeader.empty().withCompensation(emptySet(), emptySet())
        assertThat(header.match(""), equalTo(false))
    }

    @Test
    fun matchIfIncludeAll() {
        val header = DefaultHeader.empty().withCompensation(setOf("*"), emptySet())
        assertThat(header.match(""), equalTo(true))
    }

    @Test
    fun matchIfExclude() {
        val header = DefaultHeader.empty().withCompensation(setOf("*"), setOf("processor"))
        assertThat(header.match("processor"), equalTo(false))
    }
}