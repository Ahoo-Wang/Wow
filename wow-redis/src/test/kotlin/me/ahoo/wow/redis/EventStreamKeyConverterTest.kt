package me.ahoo.wow.redis

import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.redis.EventStreamKeyConverter.toKeyPrefix
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class EventStreamKeyConverterTest {
    private val aggregateId = MOCK_AGGREGATE_METADATA.asAggregateId("id", "tenantId")

    @Test
    fun toKeyPrefix() {
        val actual = aggregateId.toKeyPrefix()
        assertThat(actual, equalTo("tck:mock_aggregate:event:"))
    }

    @Test
    fun toAggregateIdKey() {
        val actual = EventStreamKeyConverter.toAggregateIdKey(aggregateId)
        assertThat(actual, equalTo("{id@tenantId}"))
    }

    @Test
    fun toAggregateId() {
        val actual = EventStreamKeyConverter.toAggregateId(MOCK_AGGREGATE_METADATA, "{id@tenantId}")
        assertThat(actual, equalTo(aggregateId))
    }

    @Test
    fun converter() {
        val actual = EventStreamKeyConverter.converter(aggregateId)
        assertThat(actual, equalTo("tck:mock_aggregate:event:{id@tenantId}"))
    }
}
