package me.ahoo.wow.modeling.matedata

import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.annotation.MockAggregate
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import org.junit.jupiter.api.Test

class CommandAggregateMetadataTest {

    @Test
    fun testEquals() {
        val aggregateMetadata =
            aggregateMetadata<MockAggregate, MockAggregate>()
        val commandAggregateMetadata = aggregateMetadata.command
        commandAggregateMetadata.equals(commandAggregateMetadata).assert().isTrue()
        commandAggregateMetadata.equals(this).assert().isFalse()

        val aggregateMetadata2 =
            aggregateMetadata<MockAggregate, MockAggregate>()
        commandAggregateMetadata.equals(aggregateMetadata2.command).assert().isTrue()
    }
}
