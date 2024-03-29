package me.ahoo.wow.sharding

import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class SingleAggregateIdShardingTest {

    @Test
    fun sharding() {
        val sharding = SingleAggregateIdSharding("test")
        val actual = sharding.sharding(MOCK_AGGREGATE_METADATA.aggregateId())
        assertThat(actual, equalTo("test"))
    }
}
