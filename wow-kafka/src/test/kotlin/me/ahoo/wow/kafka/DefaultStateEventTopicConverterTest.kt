package me.ahoo.wow.kafka

import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class DefaultStateEventTopicConverterTest {

    @Test
    fun convert() {
        val topic = DefaultStateEventTopicConverter().convert(MOCK_AGGREGATE_METADATA)
        assertThat(topic, equalTo("wow.tck.mock_aggregate.state"))
    }
}
