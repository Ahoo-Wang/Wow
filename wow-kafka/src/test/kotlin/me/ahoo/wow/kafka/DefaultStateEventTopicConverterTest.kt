package me.ahoo.wow.kafka

import me.ahoo.test.asserts.assert
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test

class DefaultStateEventTopicConverterTest {

    @Test
    fun convert() {
        val topic = DefaultStateEventTopicConverter().convert(MOCK_AGGREGATE_METADATA)
        topic.assert().isEqualTo("wow.tck.mock_aggregate.state")
    }
}
