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
import me.ahoo.wow.api.query.Sort
import org.bson.BsonTimestamp
import org.bson.Document
import org.bson.types.Decimal128
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.Date

class MongoCursorFilterCompilerTest {
    @Test
    fun `should compile mixed direction lexicographic cursor`() {
        val filter = MongoCursorFilterCompiler.compile(
            listOf(
                Sort("createdAt", Sort.Direction.DESC),
                Sort("_id", Sort.Direction.ASC),
            ),
            listOf(100, "id-1"),
        ).toBsonDocument()

        filter.toJson().assert().contains(
            "\"createdAt\": {\"\$lt\": 100}",
            "\"createdAt\": 100",
            "\"_id\": {\"\$gt\": \"id-1\"}",
        )
    }

    @Test
    fun `ascending null should continue with non null values`() {
        MongoCursorFilterCompiler.compile(
            listOf(Sort("rank", Sort.Direction.ASC)),
            listOf(null),
        ).toBsonDocument().toJson().assert().contains("\$ne")
    }

    @Test
    fun `descending null should only continue through later tie breakers`() {
        MongoCursorFilterCompiler.compile(
            listOf(
                Sort("rank", Sort.Direction.DESC),
                Sort("_id", Sort.Direction.ASC),
            ),
            listOf(null, "id-1"),
        ).toBsonDocument().toJson().assert().contains("\$expr", "\$gt")
    }

    @Test
    fun `descending value should continue through null and missing values`() {
        MongoCursorFilterCompiler.compile(
            listOf(Sort("rank", Sort.Direction.DESC)),
            listOf(1),
        ).toBsonDocument().toJson().assert().contains("\$lt", "\"rank\": null")
    }

    @Test
    fun `cursor values should match sort arity`() {
        assertThrows<IllegalArgumentException> {
            MongoCursorFilterCompiler.compile(
                listOf(Sort("rank", Sort.Direction.ASC)),
                emptyList(),
            )
        }
    }

    @Test
    fun `cursor values should be scalar`() {
        assertThrows<IllegalArgumentException> {
            MongoCursorFilterCompiler.compile(
                listOf(Sort("rank", Sort.Direction.ASC)),
                listOf(Document("nested", true)),
            )
        }
    }

    @Test
    fun `cursor filter should retain native date timestamp and decimal types`() {
        val cases = listOf(
            Date(1_725_000_000_123) to "\$date",
            BsonTimestamp(1_725_000_000, 7) to "\$timestamp",
            Decimal128(BigDecimal("1234567890.123456789")) to "\$numberDecimal",
        )

        cases.forEach { (value, bsonTypeMarker) ->
            val compiled = MongoCursorFilterCompiler.compile(
                listOf(Sort("value", Sort.Direction.ASC)),
                listOf(value),
            ).toBsonDocument()

            compiled.toJson().assert().contains(bsonTypeMarker)
        }
    }

    @Test
    fun `cursor compiler should reject more than 32 sort fields`() {
        assertThrows<IllegalArgumentException> {
            MongoCursorFilterCompiler.compile(
                List(33) { index -> Sort("field-$index", Sort.Direction.ASC) },
                List(33) { it },
            )
        }
    }
}
