package me.ahoo.wow.sharding

import me.ahoo.cosid.sharding.ModCycle
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class CompositeAggregateIdShardingTest {

    private val sharding = CompositeAggregateIdSharding(
        mapOf(
            MOCK_AGGREGATE_METADATA.materialize() to CosIdShardingDecorator(ModCycle(4, "sharding_"))
        )
    )

    @Test
    fun sharding() {
        val actual = sharding.sharding(MOCK_AGGREGATE_METADATA.asAggregateId("0TEDamtj0001001"))
        MatcherAssert.assertThat(actual, Matchers.equalTo("sharding_1"))
    }

    @Test
    fun shardingIfMissing() {
        val aggregateId = MaterializedNamedAggregate("test", "test").asAggregateId()
        Assertions.assertThrows(IllegalStateException::class.java) {
            sharding.sharding(aggregateId)
        }
    }
}
