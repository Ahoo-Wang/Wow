package me.ahoo.wow.command.factory

import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class SimpleCommandOptionsTest {

    @Test
    fun default() {
        val commandOptions = CommandOptions.builder()
        assertThat(commandOptions.id, notNullValue())
        assertThat(commandOptions.requestId, nullValue())
        assertThat(commandOptions.aggregateId, nullValue())
        assertThat(commandOptions.tenantId, nullValue())
        assertThat(commandOptions.aggregateVersion, nullValue())
        assertThat(commandOptions.namedAggregate, nullValue())
        assertThat(commandOptions.header, equalTo(DefaultHeader.empty()))
        assertThat(
            commandOptions.createTime.toDouble(),
            closeTo(System.currentTimeMillis().toDouble(), 5000.toDouble())
        )
    }

    @Test
    fun customize() {
        val commandOptions = CommandOptions.builder()
            .id("id")
            .requestId("requestId")
            .aggregateId("aggregateId")
            .tenantId("tenantId")
            .aggregateVersion(1)
            .namedAggregate(MOCK_AGGREGATE_METADATA)
            .header {
                it.with("key", "value")
            }
            .createTime(1)

        assertThat(commandOptions.id, equalTo("id"))
        assertThat(commandOptions.requestId, equalTo("requestId"))
        assertThat(commandOptions.aggregateId, equalTo("aggregateId"))
        assertThat(commandOptions.tenantId, equalTo("tenantId"))
        assertThat(commandOptions.aggregateVersion, equalTo(1))
        assertThat(commandOptions.namedAggregate, equalTo(MOCK_AGGREGATE_METADATA))
        assertThat(commandOptions.header["key"], equalTo("value"))
        assertThat(commandOptions.createTime, equalTo(1))
    }
}
