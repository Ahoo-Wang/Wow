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
    @Test
    fun `should define cursor query boundaries`() {
        val schema = JsonSerializer.readTree(Files.readString(Path.of("../schema/query/v2/cursor-query.schema.json")))

        schema.path("required").toList().map { it.stringValue() }.assert().containsExactly("filter")
        schema.path("additionalProperties").booleanValue().assert().isFalse()
        schema.path("properties").path("size").path("minimum").intValue().assert().isEqualTo(1)
        schema.path("properties").path("size").path("maximum").intValue().assert().isEqualTo(2147483646)
        schema.path("properties").path("size").path("default").intValue().assert().isEqualTo(10)
        schema.path("properties").path("sort").path("maxItems").intValue().assert().isEqualTo(32)
    }
}
