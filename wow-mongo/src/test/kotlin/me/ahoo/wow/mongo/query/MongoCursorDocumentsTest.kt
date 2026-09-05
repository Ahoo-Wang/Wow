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
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import org.bson.BsonTimestamp
import org.bson.Document
import org.bson.RawBsonDocument
import org.bson.codecs.DocumentCodec
import org.bson.types.Decimal128
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64
import java.util.Date

class MongoCursorDocumentsTest {
    @Test
    fun `BSON cursor should round trip native scalars without a key`() {
        val values = listOf(null, "x", true, 1, 2L, 1.5, Date(1), BsonTimestamp(2, 3), Decimal128(4))
        val cursor = MongoCursorCodec.encode(values)

        cursor.contains('=').assert().isFalse()
        MongoCursorCodec.decode(cursor, values.size).assert().isEqualTo(values)
    }

    @Test
    fun `BSON cursor should reject malformed arity and object values`() {
        assertThrows<IllegalArgumentException> { MongoCursorCodec.decode("not-base64", 1) }
            .message.assert().isEqualTo("Invalid cursor.")
        assertThrows<IllegalArgumentException> {
            MongoCursorCodec.decode(MongoCursorCodec.encode(listOf(1)), 2)
        }.message.assert().isEqualTo("Invalid cursor.")
        assertThrows<IllegalArgumentException> {
            MongoCursorCodec.encode(listOf(Document("nested", 1)))
        }.message.assert().isEqualTo("Invalid cursor.")
    }

    @Test
    fun `BSON cursor should reject malformed BSON and payload shapes identically`() {
        listOf(
            Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(1, 2, 3)),
            rawCursor(Document("other", listOf(1))),
            rawCursor(Document("values", Document("nested", 1))),
            rawCursor(Document("values", listOf(Document("nested", 1)))),
        ).forEach { cursor ->
            assertThrows<IllegalArgumentException> { MongoCursorCodec.decode(cursor, 1) }
                .message.assert().isEqualTo("Invalid cursor.")
        }
    }

    @Test
    fun `missing sort value should be encoded as null from last returned record`() {
        val page = listOf(Document("name", "one"), Document("rank", 2))
            .toCursorPage(cursorQuery(), Projection.ALL.withCursorFields(listOf("rank"))) { it }

        MongoCursorCodec.decode(page.nextCursor!!, 1).single().assert().isNull()
    }

    @Test
    fun `next cursor should use last returned record instead of lookahead`() {
        val page = listOf(Document("rank", 1), Document("rank", 2))
            .toCursorPage(cursorQuery(), Projection.ALL.withCursorFields(listOf("rank"))) { it }

        MongoCursorCodec.decode(page.nextCursor!!, 1).single().assert().isEqualTo(1)
    }

    @Test
    fun `included projection should remove cursor-only empty parents`() {
        val projection = Projection(include = listOf(QueryField("name"))).withCursorFields(listOf("state.createdAt"))
        val page = listOf(
            Document("name", "one").append("state", Document("createdAt", 1)),
            Document("name", "two").append("state", Document("createdAt", 2)),
        ).toCursorPage(cursorQuery(sortField = "state.createdAt"), projection) { it }

        page.list.single().containsKey("state").assert().isFalse()
        MongoCursorCodec.decode(page.nextCursor!!, 1).single().assert().isEqualTo(1)
    }

    @Test
    fun `user included sort field should remain in response`() {
        val projection = Projection(include = listOf(QueryField("state.createdAt")))
            .withCursorFields(listOf("state.createdAt"))
        val page = listOf(
            Document("state", Document("createdAt", 1)),
            Document("state", Document("createdAt", 2)),
        ).toCursorPage(cursorQuery(sortField = "state.createdAt"), projection) { it }

        page.list.single().get("state", Document::class.java).containsKey("createdAt").assert().isTrue()
    }

    @Test
    fun `excluded parent temporarily read for child sort should remain excluded`() {
        val projection = Projection(exclude = listOf(QueryField("state"), QueryField("state.createdAt")))
            .withCursorFields(listOf("state.createdAt"))
        val page = listOf(
            Document("state", Document("name", "one").append("createdAt", 1)),
            Document("state", Document("name", "two").append("createdAt", 2)),
        ).toCursorPage(cursorQuery(sortField = "state.createdAt"), projection) { it }

        projection.queryProjection.exclude.assert().isEmpty()
        page.list.single().containsKey("state").assert().isFalse()
        MongoCursorCodec.decode(page.nextCursor!!, 1).single().assert().isEqualTo(1)
    }

    @Test
    fun `excluding only cursor child should retain exclusion projection semantics`() {
        val projection = Projection(exclude = listOf(QueryField("state.createdAt")))
            .withCursorFields(listOf("state.createdAt"))
        val page = listOf(
            Document("state", Document("createdAt", 1)),
            Document("state", Document("createdAt", 2)),
        ).toCursorPage(cursorQuery(sortField = "state.createdAt"), projection) { it }

        page.list.single().get("state", Document::class.java).isEmpty().assert().isTrue()
    }

    @Test
    fun `included projection should clean each returned row and preserve shared payload and lookahead`() {
        val sortFields = listOf("state.a.hidden.rank", "state.a.hidden.weight", "state.b.hidden.rank")
        val projection = Projection(
            include = listOf(QueryField("name"), QueryField("state.a.payload"), QueryField("state.empty")),
        ).withCursorFields(sortFields)
        val documents = (1..3).map { rank ->
            Document("name", "row-$rank").append(
                "state",
                Document(
                    "a",
                    Document("hidden", Document("rank", rank).append("weight", rank + 10))
                        .append("payload", "keep-$rank"),
                ).append("b", Document("hidden", Document("rank", rank + 20)))
                    .append("empty", Document()),
            )
        }
        val query =
            CursorQuery(MatchAllFilter, sort = sortFields.map { Sort(QueryField(it), Sort.Direction.ASC) }, size = 2)

        val page = documents.toCursorPage(query, projection) { it }

        page.list.assert().containsExactly(
            Document("name", "row-1").append(
                "state",
                Document("a", Document("payload", "keep-1")).append("empty", Document()),
            ),
            Document("name", "row-2").append(
                "state",
                Document("a", Document("payload", "keep-2")).append("empty", Document()),
            ),
        )
        MongoCursorCodec.decode(page.nextCursor!!, 3).assert().isEqualTo(listOf(2, 12, 22))
        documents.last().assert().isEqualTo(
            Document("name", "row-3").append(
                "state",
                Document("a", Document("hidden", Document("rank", 3).append("weight", 13)).append("payload", "keep-3"))
                    .append("b", Document("hidden", Document("rank", 23)))
                    .append("empty", Document()),
            ),
        )
    }

    @Test
    fun `deferred identity should remain native for cursor and reach mapper once in row order`() {
        val sortFields = listOf("_id", "state.rank")
        val projection = Projection(include = listOf(QueryField("name"))).withCursorFields(sortFields)
        val documents = (1..3).map { rank ->
            Document("_id", 100L + rank).append("name", "row-$rank").append("state", Document("rank", rank))
        }
        val query =
            CursorQuery(MatchAllFilter, sort = sortFields.map { Sort(QueryField(it), Sort.Direction.ASC) }, size = 2)
        val mappedIds = mutableListOf<Long>()

        val page = documents.toCursorPage(query, projection, deferredInternalFields = setOf("_id")) { document ->
            document.containsKey("state").assert().isFalse()
            mappedIds += document.remove("_id") as Long
            document.getString("name")
        }

        mappedIds.assert().containsExactly(101L, 102L)
        page.list.assert().containsExactly("row-1", "row-2")
        MongoCursorCodec.decode(page.nextCursor!!, 2).assert().isEqualTo(listOf<Any>(102L, 2))
    }

    @Test
    fun `empty terminal page should not invoke mapper or return a token`() {
        val projection = Projection(include = listOf(QueryField("name"))).withCursorFields(listOf("rank"))

        val page = emptyList<Document>().toCursorPage(cursorQuery(), projection) {
            error("Mapper must not run for an empty page.")
        }

        page.list.assert().isEmpty()
        page.nextCursor.assert().isNull()
    }

    private fun cursorQuery(sortField: String = "rank") = CursorQuery(
        MatchAllFilter,
        sort = listOf(Sort(QueryField(sortField), Sort.Direction.ASC)),
        size = 1,
    )

    private fun rawCursor(document: Document): String {
        val raw = RawBsonDocument(document, DocumentCodec())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            raw.backingArray.copyOfRange(raw.byteOffset, raw.byteOffset + raw.byteLength),
        )
    }
}
