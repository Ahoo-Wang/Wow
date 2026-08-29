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

package me.ahoo.wow.schema.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class CursorQuerySchemaTest {
    private val schema = JsonSerializer.readTree(
        Files.readString(Path.of("../schema/query/v2/cursor-query.schema.json")),
    )

    @Test
    fun `cursor query schema should require filter and bound size and sort`() {
        schema["required"].toList().map { it.stringValue() }.assert().containsExactly("filter")
        schema["additionalProperties"].booleanValue().assert().isFalse()
        val size = schema["properties"]["size"]
        size["minimum"].intValue().assert().isOne()
        size["maximum"].intValue().assert().isEqualTo(Int.MAX_VALUE - 1)
        schema["properties"]["sort"]["maxItems"].intValue()
            .assert().isEqualTo(me.ahoo.wow.api.query.AggregationQuery.MAX_SORT_FIELDS)
    }
}
