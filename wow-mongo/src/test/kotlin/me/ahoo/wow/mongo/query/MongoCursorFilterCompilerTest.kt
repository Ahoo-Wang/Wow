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

package me.ahoo.wow.mongo.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import org.bson.Document
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MongoCursorFilterCompilerTest {
    @Test
    fun `should compile mixed direction lexicographic cursor`() {
        MongoCursorFilterCompiler.compile(
            listOf(Sort(QueryField("createdAt"), Sort.Direction.DESC), Sort(QueryField("_id"), Sort.Direction.ASC)),
            listOf(100, "id-1"),
        ).toBsonDocument().toJson().assert().contains(
            "\"createdAt\": {\"\$lt\": 100}",
            "\"createdAt\": 100",
            "\"_id\": {\"\$gt\": \"id-1\"}",
        )
    }

    @Test
    fun `should preserve Mongo null and missing ordering`() {
        MongoCursorFilterCompiler.compile(
            listOf(Sort(QueryField("rank"), Sort.Direction.ASC)),
            listOf(null),
        ).toBsonDocument().toJson().assert().contains("\$ne")

        MongoCursorFilterCompiler.compile(
            listOf(Sort(QueryField("rank"), Sort.Direction.DESC)),
            listOf(1),
        ).toBsonDocument().toJson().assert().contains("\$lt", "\"rank\": null")

        MongoCursorFilterCompiler.compile(
            listOf(Sort(QueryField("rank"), Sort.Direction.DESC), Sort(QueryField("_id"), Sort.Direction.ASC)),
            listOf(null, "id-1"),
        ).toBsonDocument().toJson().assert().contains("\$expr", "\$gt")
    }

    @Test
    fun `should reject arity mismatch and object values`() {
        assertThrows<IllegalArgumentException> {
            MongoCursorFilterCompiler.compile(
                listOf(Sort(QueryField("rank"), Sort.Direction.ASC)),
                emptyList(),
            )
        }
        assertThrows<IllegalArgumentException> {
            MongoCursorFilterCompiler.compile(
                listOf(Sort(QueryField("rank"), Sort.Direction.ASC)),
                listOf(Document("nested", 1)),
            )
        }
    }
}
