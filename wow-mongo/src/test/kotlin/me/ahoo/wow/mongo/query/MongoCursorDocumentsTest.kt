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
        val projection = Projection(include = listOf("name")).withCursorFields(listOf("state.createdAt"))
        val page = listOf(
            Document("name", "one").append("state", Document("createdAt", 1)),
            Document("name", "two").append("state", Document("createdAt", 2)),
        ).toCursorPage(cursorQuery(sortField = "state.createdAt"), projection) { it }

        page.list.single().containsKey("state").assert().isFalse()
        MongoCursorCodec.decode(page.nextCursor!!, 1).single().assert().isEqualTo(1)
    }

    @Test
    fun `user included sort field should remain in response`() {
        val projection = Projection(include = listOf("state.createdAt"))
            .withCursorFields(listOf("state.createdAt"))
        val page = listOf(
            Document("state", Document("createdAt", 1)),
            Document("state", Document("createdAt", 2)),
        ).toCursorPage(cursorQuery(sortField = "state.createdAt"), projection) { it }

        page.list.single().get("state", Document::class.java).containsKey("createdAt").assert().isTrue()
    }

    @Test
    fun `excluded parent temporarily read for child sort should remain excluded`() {
        val projection = Projection(exclude = listOf("state", "state.createdAt"))
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
        val projection = Projection(exclude = listOf("state.createdAt"))
            .withCursorFields(listOf("state.createdAt"))
        val page = listOf(
            Document("state", Document("createdAt", 1)),
            Document("state", Document("createdAt", 2)),
        ).toCursorPage(cursorQuery(sortField = "state.createdAt"), projection) { it }

        page.list.single().get("state", Document::class.java).isEmpty().assert().isTrue()
    }

    private fun cursorQuery(sortField: String = "rank") = CursorQuery(
        MatchAllFilter,
        sort = listOf(Sort(sortField, Sort.Direction.ASC)),
        size = 1,
    )

    private fun rawCursor(document: Document): String {
        val raw = RawBsonDocument(document, DocumentCodec())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            raw.backingArray.copyOfRange(raw.byteOffset, raw.byteOffset + raw.byteLength),
        )
    }
}
