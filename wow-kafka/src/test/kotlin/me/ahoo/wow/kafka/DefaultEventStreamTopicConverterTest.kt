package me.ahoo.wow.kafka

import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class DefaultEventStreamTopicConverterTest {

    @Test
    fun convert() {
        val topic = DefaultEventStreamTopicConverter().convert(MOCK_AGGREGATE_METADATA)
        assertThat(topic, equalTo("wow.wow-tck.mock_aggregate.event"))
    }
}
