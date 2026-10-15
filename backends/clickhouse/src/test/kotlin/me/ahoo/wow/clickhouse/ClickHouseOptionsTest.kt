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

package me.ahoo.wow.clickhouse

import me.ahoo.test.asserts.assert
import me.ahoo.wow.clickhouse.query.ClickHouseDatasetOptions
import me.ahoo.wow.clickhouse.sink.AggregateSinkOptions
import me.ahoo.wow.clickhouse.sink.ClickHouseSinkOptions
import org.junit.jupiter.api.Test

/** The options are a plain class (decision D216) that keeps a data class's equality, hash and string. */
class ClickHouseOptionsTest {
    @Test
    fun `an option equals another with the same fields, and prints them`() {
        valueSemantics(AggregateSinkOptions(), AggregateSinkOptions(), AggregateSinkOptions(history = false))
        valueSemantics(ClickHouseSinkOptions(), ClickHouseSinkOptions(), ClickHouseSinkOptions(batchSize = 1))
        valueSemantics(
            ClickHouseDatasetOptions(),
            ClickHouseDatasetOptions(),
            ClickHouseDatasetOptions(database = "wow")
        )
    }

    private fun valueSemantics(value: Any, same: Any, other: Any) {
        (value == value).assert().isTrue()
        (value == same).assert().isTrue()
        value.hashCode().assert().isEqualTo(same.hashCode())
        (value == other).assert().isFalse()
        (value == value.javaClass.simpleName).assert().isFalse()
        value.toString().assert().startsWith("${value.javaClass.simpleName}(").isEqualTo(same.toString())
    }
}
