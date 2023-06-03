package me.ahoo.wow.redis

import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class DefaultSnapshotKeyConverterTest {

    @Test
    fun converter() {
        val aggregateId = MOCK_AGGREGATE_METADATA.asAggregateId("id", "tenantId")
        val actual = DefaultSnapshotKeyConverter.converter(aggregateId)
        assertThat(actual, equalTo("tck:mock_aggregate:snapshot:{id}"))
    }
}
