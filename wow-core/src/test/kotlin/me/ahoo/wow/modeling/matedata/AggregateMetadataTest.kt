package me.ahoo.wow.modeling.matedata

import me.ahoo.wow.modeling.annotation.MockAggregate
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test

class AggregateMetadataTest {

    @Test
    fun testEquals() {
        val aggregateMetadata =
            aggregateMetadata<MockAggregate, MockAggregate>()

        assertThat(aggregateMetadata.equals(aggregateMetadata), equalTo(true))
        assertThat(aggregateMetadata.equals(this), equalTo(false))

        val aggregateMetadata2 =
            aggregateMetadata<MockAggregate, MockAggregate>()
        assertThat(aggregateMetadata.equals(aggregateMetadata2), equalTo(true))
    }
}
