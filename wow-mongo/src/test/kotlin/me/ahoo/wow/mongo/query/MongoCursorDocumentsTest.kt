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
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.CursorPosition
import org.bson.BsonTimestamp
import org.bson.Document
import org.bson.RawBsonDocument
import org.bson.codecs.DocumentCodec
import org.bson.types.Decimal128
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Date

class MongoCursorDocumentsTest {
    @Test
    fun `BSON positions should round trip native scalars`() {
        val values = listOf(null, "x", true, 1, 2L, 1.5, Date(1), BsonTimestamp(2, 3), Decimal128(4))
        val payload = MongoCursorCodec.encode(CursorPosition(values))

        MongoCursorCodec.decode(payload, values.size).values.assert().isEqualTo(values)
    }

    @Test
    fun `BSON positions should reject malformed arity and object values`() {
        assertThrows<Exception> { MongoCursorCodec.decode(byteArrayOf(1, 2, 3), 1) }
        assertThrows<IllegalArgumentException> {
            MongoCursorCodec.decode(MongoCursorCodec.encode(CursorPosition(listOf(1))), 2)
        }
        assertThrows<IllegalArgumentException> {
            MongoCursorCodec.encode(CursorPosition(listOf(Document("nested", 1))))
        }
    }

    @Test
    fun `BSON positions should reject malformed payload shapes`() {
        listOf(
            raw(Document("other", listOf(1))),
            raw(Document("values", Document("nested", 1))),
            raw(Document("values", listOf(Document("nested", 1)))),
        ).forEach { payload ->
            assertThrows<Exception> { MongoCursorCodec.decode(payload, 1) }
        }
    }

    @Test
    fun `a missing sort value is a null position value`() {
        val window = listOf(Document("name", "one"), Document("rank", 2))
            .toKeysetRows(Projection.ALL.withCursorFields(listOf("rank")), listOf("rank")) { it }

        window.positions.map { it.values.single() }.assert().containsExactly(null, 2)
    }

    @Test
    fun `included projection should remove cursor-only empty parents after reading the position`() {
        val projection = Projection(include = listOf(QueryField("name"))).withCursorFields(listOf("state.createdAt"))
        val window = listOf(
            Document("name", "one").append("state", Document("createdAt", 1)),
            Document("name", "two").append("state", Document("createdAt", 2)),
        ).toKeysetRows(projection, listOf("state.createdAt")) { it }

        window.rows.forEach { it.containsKey("state").assert().isFalse() }
        window.positions.map { it.values.single() }.assert().containsExactly(1, 2)
    }

    @Test
    fun `user included sort field should remain in response`() {
        val projection = Projection(include = listOf(QueryField("state.createdAt")))
            .withCursorFields(listOf("state.createdAt"))
        val window = listOf(
            Document("state", Document("createdAt", 1)),
        ).toKeysetRows(projection, listOf("state.createdAt")) { it }

        window.rows.single().get("state", Document::class.java).containsKey("createdAt").assert().isTrue()
    }

    @Test
    fun `excluded parent temporarily read for child sort should remain excluded`() {
        val projection = Projection(exclude = listOf(QueryField("state"), QueryField("state.createdAt")))
            .withCursorFields(listOf("state.createdAt"))
        val window = listOf(
            Document("state", Document("name", "one").append("createdAt", 1)),
        ).toKeysetRows(projection, listOf("state.createdAt")) { it }

        projection.queryProjection.exclude.assert().isEmpty()
        window.rows.single().containsKey("state").assert().isFalse()
        window.positions.single().values.single().assert().isEqualTo(1)
    }

    @Test
    fun `excluding only cursor child should retain exclusion projection semantics`() {
        val projection = Projection(exclude = listOf(QueryField("state.createdAt")))
            .withCursorFields(listOf("state.createdAt"))
        val window = listOf(
            Document("state", Document("createdAt", 1)),
        ).toKeysetRows(projection, listOf("state.createdAt")) { it }

        window.rows.single().get("state", Document::class.java).isEmpty().assert().isTrue()
    }

    @Test
    fun `included projection should clean every row and keep shared payload`() {
        val sortFields = listOf("state.a.hidden.rank", "state.a.hidden.weight", "state.b.hidden.rank")
        val projection = Projection(
            include = listOf(QueryField("name"), QueryField("state.a.payload"), QueryField("state.empty")),
        ).withCursorFields(sortFields)
        val documents = (1..2).map { rank ->
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

        val window = documents.toKeysetRows(projection, sortFields) { it }

        window.rows.assert().containsExactly(
            Document("name", "row-1").append(
                "state",
                Document("a", Document("payload", "keep-1")).append("empty", Document()),
            ),
            Document("name", "row-2").append(
                "state",
                Document("a", Document("payload", "keep-2")).append("empty", Document()),
            ),
        )
        window.positions.map { it.values }.assert().containsExactly(listOf(1, 11, 21), listOf(2, 12, 22))
    }

    @Test
    fun `deferred identity should remain native for the position and reach the mapper once in row order`() {
        val sortFields = listOf("_id", "state.rank")
        val projection = Projection(include = listOf(QueryField("name"))).withCursorFields(sortFields)
        val documents = (1..2).map { rank ->
            Document("_id", 100L + rank).append("name", "row-$rank").append("state", Document("rank", rank))
        }
        val mappedIds = mutableListOf<Long>()

        val window = documents.toKeysetRows(projection, sortFields, deferredInternalFields = setOf("_id")) { document ->
            document.containsKey("state").assert().isFalse()
            mappedIds += document.remove("_id") as Long
            document.getString("name")
        }

        mappedIds.assert().containsExactly(101L, 102L)
        window.rows.assert().containsExactly("row-1", "row-2")
        window.positions.last().values.assert().isEqualTo(listOf<Any>(102L, 2))
    }

    @Test
    fun `an empty window should not invoke the mapper`() {
        val projection = Projection(include = listOf(QueryField("name"))).withCursorFields(listOf("rank"))

        val window = emptyList<Document>().toKeysetRows(projection, listOf("rank")) {
            error("Mapper must not run for an empty page.")
        }

        window.rows.assert().isEmpty()
        window.positions.assert().isEmpty()
    }

    private fun raw(document: Document): ByteArray {
        val raw = RawBsonDocument(document, DocumentCodec())
        return raw.backingArray.copyOfRange(raw.byteOffset, raw.byteOffset + raw.byteLength)
    }
}
