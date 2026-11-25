/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.clickhouse.review

import me.ahoo.test.asserts.assert
import me.ahoo.wow.clickhouse.sink.ClickHouseSinkProvider
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.sink.SinkConfig
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Review r2 (configuration keys): the ClickHouse Sink refuses a batch size or a deduplication window below one, and a
 * negative insert interval, with `ClickHouseSinkOptions`' bare `IllegalArgumentException` ("A batch holds at least one
 * record."), which names no key, where `operate/configuration.md` promises a `WowConfigurationException` naming the key
 * and its value.
 */
class R2ClickHouseSinkOptionRefusalTest {
    @ParameterizedTest
    @CsvSource("sink.batch-size,0", "sink.deduplication-window,0", "sink.insert-interval,-1s")
    fun `a sink option out of range should be refused naming its key`(option: String, value: String) {
        val config = SinkConfig.of(
            "clickhouse",
            "clickhouse",
            mapOf("endpoint" to "http://127.0.0.1:1", option to value),
        )
        val failure = assertThrows<WowConfigurationException> { ClickHouseSinkProvider.options(config) }
        failure.key.assert().endsWith(option)
    }
}
