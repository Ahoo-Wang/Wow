package me.ahoo.wow.modeling.matedata

import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.annotation.MockAggregate
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import org.junit.jupiter.api.Test

class AggregateMetadataTest {

    @Test
    fun testEquals() {
        val aggregateMetadata =
            aggregateMetadata<MockAggregate, MockAggregate>()
        aggregateMetadata.equals(aggregateMetadata).assert().isTrue()
        aggregateMetadata.equals(this).assert().isFalse()
        val aggregateMetadata2 =
            aggregateMetadata<MockAggregate, MockAggregate>()
        aggregateMetadata2.equals(aggregateMetadata).assert().isTrue()
    }
}
