package me.ahoo.wow.redis.bus

import me.ahoo.test.asserts.assert
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test

class DefaultEventStreamTopicConverterTest {
    @Test
    fun convert() {
        val actual = DefaultEventStreamTopicConverter.convert(MOCK_AGGREGATE_METADATA)
        actual.assert().isEqualTo("tck.mock_aggregate:event")
    }
}
