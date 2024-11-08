package me.ahoo.wow.modeling.matedata

import me.ahoo.wow.modeling.annotation.MockAggregate
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test

class CommandAggregateMetadataTest {

    @Test
    fun testEquals() {
        val aggregateMetadata =
            aggregateMetadata<MockAggregate, MockAggregate>()
        val commandAggregateMetadata = aggregateMetadata.command
        assertThat(commandAggregateMetadata.equals(commandAggregateMetadata), equalTo(true))
        assertThat(commandAggregateMetadata.equals(this), equalTo(false))

        val aggregateMetadata2 =
            aggregateMetadata<MockAggregate, MockAggregate>()
        assertThat(commandAggregateMetadata.equals(aggregateMetadata2.command), equalTo(true))
    }
}
