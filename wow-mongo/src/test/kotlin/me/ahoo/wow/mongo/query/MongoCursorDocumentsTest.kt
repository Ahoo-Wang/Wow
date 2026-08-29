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
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.mongo.query.snapshot.SnapshotFieldConverter
import me.ahoo.wow.query.CursorTokenCodec
import org.bson.BsonTimestamp
import org.bson.Document
import org.bson.types.Decimal128
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.Base64
import java.util.Date

class MongoCursorDocumentsTest {
    private val tokenCodec = CursorTokenCodec.fromBase64Url(
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() }),
    )

    @Test
    fun `missing sort value should be encoded as null`() {
        val query = cursorQuery(sort = listOf(Sort("rank", Sort.Direction.ASC)))

        val page = listOf(Document("name", "one"), Document("name", "two"))
            .toCursorPage(query, Projection.ALL.withCursorFields(listOf("rank")), tokenCodec = tokenCodec) { it }

        MongoCursorCodec.decode(tokenCodec, page.nextCursor!!, expectedSize = 1).single().assert().isNull()
    }

    @Test
    fun `next cursor should use last returned document instead of lookahead`() {
        val query = cursorQuery(sort = listOf(Sort("rank", Sort.Direction.ASC)))

        val page = listOf(Document("rank", 1), Document("rank", 2))
            .toCursorPage(query, Projection.ALL.withCursorFields(listOf("rank")), tokenCodec = tokenCodec) { it }

        MongoCursorCodec.decode(tokenCodec, page.nextCursor!!, expectedSize = 1).single().assert().isEqualTo(1)
    }

    @Test
    fun `included projection should not leak internally included sort field`() {
        val projection = Projection(include = listOf("state.name"))
            .withCursorFields(listOf("state.createdAt"))
        val query = cursorQuery(
            projection = Projection(include = listOf("state.name")),
            sort = listOf(Sort("state.createdAt", Sort.Direction.ASC)),
        )

        projection.queryProjection.include.assert().containsExactly("state.name", "state.createdAt")
        val page = listOf(
            Document("state", Document("name", "one").append("createdAt", 1)),
            Document("state", Document("name", "two").append("createdAt", 2)),
        ).toCursorPage(query, projection, tokenCodec = tokenCodec) { it }

        page.list.single().get("state", Document::class.java).containsKey("createdAt").assert().isFalse()
        MongoCursorCodec.decode(tokenCodec, page.nextCursor!!, expectedSize = 1).single().assert().isEqualTo(1)
    }

    @Test
    fun `included projection should not leave empty parent for internal sort field`() {
        val projection = Projection(include = listOf("name"))
            .withCursorFields(listOf("state.createdAt"))
        val query = cursorQuery(
            projection = Projection(include = listOf("name")),
            sort = listOf(Sort("state.createdAt", Sort.Direction.ASC)),
        )

        val page = listOf(
            Document("name", "one").append("state", Document("createdAt", 1)),
            Document("name", "two").append("state", Document("createdAt", 2)),
        ).toCursorPage(query, projection, tokenCodec = tokenCodec) { it }

        page.list.single().containsKey("state").assert().isFalse()
        MongoCursorCodec.decode(tokenCodec, page.nextCursor!!, expectedSize = 1).single().assert().isEqualTo(1)
    }

    @Test
    fun `include projection should remove exclusion for internal cursor field`() {
        val projection = Projection(
            include = listOf("state.name"),
            exclude = listOf("aggregateId"),
        ).withCursorFields(listOf("aggregateId"))

        projection.queryProjection.exclude.assert().isEmpty()
        MongoProjectionConverter(SnapshotFieldConverter).convert(projection.queryProjection)!!
            .toBsonDocument().toJson().assert()
            .contains("\"state.name\": 1", "\"_id\": 1")
            .doesNotContain("\"_id\": 0")
    }

    @Test
    fun `user included sort field should remain in response`() {
        val projection = Projection(include = listOf("state.createdAt"))
            .withCursorFields(listOf("state.createdAt"))
        val query = cursorQuery(
            projection = Projection(include = listOf("state.createdAt")),
            sort = listOf(Sort("state.createdAt", Sort.Direction.ASC)),
        )

        val page = listOf(
            Document("state", Document("createdAt", 1)),
            Document("state", Document("createdAt", 2)),
        ).toCursorPage(query, projection, tokenCodec = tokenCodec) { it }

        page.list.single().get("state", Document::class.java).containsKey("createdAt").assert().isTrue()
    }

    @Test
    fun `excluded parent temporarily read for child sort should remain excluded`() {
        val projection = Projection(exclude = listOf("state", "state.createdAt"))
            .withCursorFields(listOf("state.createdAt"))
        val query = cursorQuery(
            projection = Projection(exclude = listOf("state", "state.createdAt")),
            sort = listOf(Sort("state.createdAt", Sort.Direction.ASC)),
        )

        projection.queryProjection.exclude.assert().isEmpty()
        val page = listOf(
            Document("state", Document("name", "one").append("createdAt", 1)),
            Document("state", Document("name", "two").append("createdAt", 2)),
        ).toCursorPage(query, projection, tokenCodec = tokenCodec) { it }

        page.list.single().containsKey("state").assert().isFalse()
        MongoCursorCodec.decode(tokenCodec, page.nextCursor!!, expectedSize = 1).single().assert().isEqualTo(1)
    }

    @Test
    fun `Mongo cursor payload should round trip BSON native scalar values losslessly`() {
        val values = listOf(
            null,
            "value",
            true,
            1,
            2L,
            1.5,
            Date(1_725_000_000_123),
            BsonTimestamp(1_725_000_000, 7),
            Decimal128(BigDecimal("1234567890.123456789")),
        )

        val decoded = MongoCursorCodec.decode(
            tokenCodec,
            MongoCursorCodec.encode(tokenCodec, values),
            expectedSize = values.size,
        )

        decoded.zip(values).forEach { (actual, expected) ->
            actual.assert().isEqualTo(expected)
            actual?.javaClass.assert().isEqualTo(expected?.javaClass)
        }
    }

    @Test
    fun `Mongo cursor payload should reject malformed structure arity and excessive values`() {
        assertThrows<IllegalArgumentException> {
            MongoCursorCodec.decode(tokenCodec, tokenCodec.encode("not-bson".toByteArray()), expectedSize = 1)
        }
        val oneValue = MongoCursorCodec.encode(tokenCodec, listOf(1))
        assertThrows<IllegalArgumentException> {
            MongoCursorCodec.decode(tokenCodec, oneValue, expectedSize = 2)
        }
        assertThrows<IllegalArgumentException> {
            MongoCursorCodec.encode(tokenCodec, List(33) { it })
        }
    }

    @Test
    fun `Mongo cursor token should not expose raw sort value`() {
        val rawValue = "masked-sort-value-should-never-appear"
        val query = cursorQuery(sort = listOf(Sort("rank", Sort.Direction.ASC)))

        val page = listOf(Document("rank", rawValue), Document("rank", "lookahead"))
            .toCursorPage(query, Projection.ALL.withCursorFields(listOf("rank")), tokenCodec = tokenCodec) { it }

        page.nextCursor!!.contains(rawValue).assert().isFalse()
        Base64.getUrlDecoder().decode(page.nextCursor).toString(Charsets.ISO_8859_1)
            .contains(rawValue).assert().isFalse()
    }

    private fun cursorQuery(
        projection: Projection = Projection.ALL,
        sort: List<Sort>,
    ) = CursorQuery(MatchAllFilter, projection = projection, sort = sort, size = 1)
}
