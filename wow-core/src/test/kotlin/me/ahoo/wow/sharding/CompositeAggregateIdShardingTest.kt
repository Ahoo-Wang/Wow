package me.ahoo.wow.sharding

import me.ahoo.cosid.sharding.ModCycle
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test

class CompositeAggregateIdShardingTest {

    private val sharding = CompositeAggregateIdSharding(
        mapOf(
            MOCK_AGGREGATE_METADATA.materialize() to CosIdShardingDecorator(ModCycle(4, "sharding_")),
        ),
    )

    @Test
    fun sharding() {
        val actual = sharding.sharding(MOCK_AGGREGATE_METADATA.aggregateId("0TEDamtj0001001"))
        actual.assert().isEqualTo("sharding_1")
    }

    @Test
    fun shardingIfMissing() {
        val aggregateId = MaterializedNamedAggregate("test", "test").aggregateId()
        assertThrownBy<IllegalStateException> {
            sharding.sharding(aggregateId)
        }
    }
}
