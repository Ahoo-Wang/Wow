package me.ahoo.wow.redis.bus

import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class DefaultStateEventTopicConverterTest {

    @Test
    fun convert() {
        val actual = DefaultStateEventTopicConverter.convert(MOCK_AGGREGATE_METADATA)
        assertThat(actual, equalTo("tck.mock_aggregate:state"))
    }
}
