package me.ahoo.wow.command.factory

import me.ahoo.wow.command.factory.CommandBuilder.Companion.commandBuilder
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class MutableCommandBuilderTest {
    @Test
    fun default() {
        val commandBuilder = this.commandBuilder()
        assertThat(commandBuilder.body, sameInstance(this))
        assertThat(commandBuilder.id, notNullValue())
        assertThat(commandBuilder.requestId, nullValue())
        assertThat(commandBuilder.aggregateId, nullValue())
        assertThat(commandBuilder.tenantId, nullValue())
        assertThat(commandBuilder.aggregateVersion, nullValue())
        assertThat(commandBuilder.namedAggregate, nullValue())
        assertThat(commandBuilder.header, equalTo(DefaultHeader.empty()))
        assertThat(
            commandBuilder.createTime.toDouble(),
            closeTo(System.currentTimeMillis().toDouble(), 5000.toDouble())
        )
    }

    @Test
    fun customize() {
        val newBody = Any()
        val commandBuilder = this.commandBuilder()
            .id("id")
            .requestIfIfAbsent("requestId")
            .aggregateId("aggregateId")
            .tenantIdIfAbsent("tenantId")
            .aggregateVersion(1)
            .namedAggregate(MOCK_AGGREGATE_METADATA)
            .header {
                it.with("key", "value")
            }
            .createTime(1)
            .body(newBody)

        assertThat(commandBuilder.id, equalTo("id"))
        assertThat(commandBuilder.requestId, equalTo("requestId"))
        assertThat(commandBuilder.aggregateId, equalTo("aggregateId"))
        assertThat(commandBuilder.tenantId, equalTo("tenantId"))
        assertThat(commandBuilder.aggregateVersion, equalTo(1))
        assertThat(commandBuilder.namedAggregate, equalTo(MOCK_AGGREGATE_METADATA))
        assertThat(commandBuilder.header["key"], equalTo("value"))
        assertThat(commandBuilder.createTime, equalTo(1))

        commandBuilder.requestIfIfAbsent("requestId2")
            .tenantIdIfAbsent("tenantId2")

        assertThat(commandBuilder.requestId, equalTo("requestId"))
        assertThat(commandBuilder.tenantId, equalTo("tenantId"))
        assertThat(commandBuilder.bodyAs(), sameInstance(newBody))

        commandBuilder.header(DefaultHeader.empty())
        assertThat(commandBuilder.header, equalTo(DefaultHeader.empty()))
    }
}
