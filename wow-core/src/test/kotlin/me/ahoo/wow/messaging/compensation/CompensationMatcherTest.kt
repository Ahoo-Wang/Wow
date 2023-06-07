package me.ahoo.wow.messaging.compensation

import me.ahoo.wow.command.asCommandMessage
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.compensation.CompensationMatcher.match
import me.ahoo.wow.messaging.compensation.CompensationMatcher.withCompensation
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class CompensationMatcherTest {
    private val processorName = "processor"

    @Test
    fun matchIfNull() {
        assertThat(DefaultHeader.empty().match(processorName), equalTo(true))
    }

    @Test
    fun matchIfEmpty() {
        val header = DefaultHeader.empty().withCompensation(emptySet(), emptySet())
        assertThat(header.match(processorName), equalTo(false))
    }

    @Test
    fun matchIfIncludeAll() {
        val header = DefaultHeader.empty().withCompensation(setOf("*"), emptySet())
        assertThat(header.match(processorName), equalTo(true))
    }

    @Test
    fun matchIfExclude() {
        val header = DefaultHeader.empty().withCompensation(setOf("*"), setOf(processorName))
        assertThat(header.match(processorName), equalTo(false))
    }

    @Test
    fun matchMessage() {
        val command = MockCreateAggregate(
            GlobalIdGenerator.generateAsString(),
            GlobalIdGenerator.generateAsString()
        ).asCommandMessage()
        assertThat(command.match(processorName), equalTo(true))
    }

    @Test
    fun matchMessageWithCompensation() {
        val command = MockCreateAggregate(
            GlobalIdGenerator.generateAsString(),
            GlobalIdGenerator.generateAsString()
        ).asCommandMessage()
            .withCompensation()
        assertThat(command.match(processorName), equalTo(false))
    }
}
