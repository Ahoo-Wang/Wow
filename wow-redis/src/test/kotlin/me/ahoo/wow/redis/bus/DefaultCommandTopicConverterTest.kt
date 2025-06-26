package me.ahoo.wow.redis.bus

import me.ahoo.test.asserts.assert
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test

class DefaultCommandTopicConverterTest {

    @Test
    fun convert() {
        val actual = DefaultCommandTopicConverter.convert(MOCK_AGGREGATE_METADATA)
        actual.assert().isEqualTo("tck.mock_aggregate:command")
    }
}
