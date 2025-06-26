package me.ahoo.wow.sharding

import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test

class SingleAggregateIdShardingTest {

    @Test
    fun sharding() {
        val sharding = SingleAggregateIdSharding("test")
        val actual = sharding.sharding(MOCK_AGGREGATE_METADATA.aggregateId())
        actual.assert().isEqualTo("test")
    }
}
