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

import com.mongodb.MongoCommandException
import com.mongodb.MongoExecutionTimeoutException
import com.mongodb.ServerAddress
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.CursorPosition
import me.ahoo.wow.query.QueryAdmission
import me.ahoo.wow.query.QueryTimeoutException
import org.bson.BsonDocument
import org.bson.Document
import org.bson.types.Decimal128
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration

class MongoQueryPartsTest {
    private val schema = MongoQueryTestSchemas.SCHEMA

    private fun admitted(projection: Projection) =
        QueryAdmission.Trusted.list(ListQuery(MatchAllFilter, projection = projection), schema)

    @Test
    fun `a position keeps each BSON scalar exactly`() {
        val position = CursorPosition(listOf(null, "a", true, 1, 2L, 1.5, Decimal128(BigDecimal("0.10"))))
        val decoded = MongoCursorCodec.decode(MongoCursorCodec.encode(position), position.values.size)
        decoded.assert().isEqualTo(position)
        assertThrownBy<IllegalArgumentException> { MongoCursorCodec.encode(CursorPosition(listOf(Document()))) }
        assertThrownBy<IllegalArgumentException> { MongoCursorCodec.decode(MongoCursorCodec.encode(position), 2) }
        val other = BsonDocument.parse("""{"other": [1]}""")
        val bytes = org.bson.RawBsonDocument(other, org.bson.codecs.BsonDocumentCodec()).byteBuffer.asNIO()
        assertThrownBy<IllegalArgumentException> {
            MongoCursorCodec.decode(ByteArray(bytes.remaining()).also { bytes.get(it) }, 1)
        }
    }

    @Test
    fun `the rows after a position honor each direction and its nulls`() {
        val sort =
            listOf(
                Sort(QueryField("state.name"), Sort.Direction.ASC),
                Sort(QueryField("aggregate_id"), Sort.Direction.DESC)
            )
        MongoKeyset.after(sort, listOf("n", "a")).toBsonDocument().toJson().assert().isEqualTo(
            """{"${'$'}or": [{"state.name": {"${'$'}gt": "n"}}, {"${'$'}and": [{"state.name": "n"}, """ +
                """{"${'$'}or": [{"aggregate_id": {"${'$'}lt": "a"}}, {"aggregate_id": null}]}]}]}""",
        )
        MongoKeyset.after(sort, listOf(null, null)).toBsonDocument().toJson().assert().isEqualTo(
            """{"${'$'}or": [{"state.name": {"${'$'}ne": null}}, {"${'$'}and": [{"state.name": null}, {"_id": {"${'$'}in": []}}]}]}""",
        )
        assertThrownBy<IllegalArgumentException> { MongoKeyset.after(sort, listOf("n")) }
        assertThrownBy<IllegalArgumentException> { MongoKeyset.after(sort, listOf("n", Document())) }
        MongoKeyset.sort(emptyList()).assert().isNull()
        MongoKeyset.valueAt(Document("state", Document("name", "n")), "state.name").assert().isEqualTo("n")
        MongoKeyset.valueAt(Document("state", "text"), "state.name").assert().isNull()
    }

    @Test
    fun `MongoDB returns a superset of the projection, two segments deep at most`() {
        fun native(projection: Projection, sort: List<String> = emptyList()): String =
            MongoProjections.native(admitted(projection), sort).toBsonDocument().toJson()
        native(Projection.ALL).assert().isEqualTo("""{"_id": 0}""")
        native(
            Projection(include = listOf(QueryField("tenantId"), QueryField("state.lines.sku"), QueryField("state")))
        ).assert()
            .isEqualTo("""{"tenant_id": 1, "state": 1, "_id": 0}""")
        native(Projection(include = listOf(QueryField("aggregateId"))), listOf("state.amount", "aggregate_id")).assert()
            .isEqualTo("""{"aggregate_id": 1, "state.amount": 1, "_id": 0}""")
        native(
            Projection(exclude = listOf(QueryField("state.lines.sku"), QueryField("tags"), QueryField("deleted")))
        ).assert()
            .isEqualTo("""{"tags": 0, "deleted": 0, "_id": 0}""")
        native(
            Projection(exclude = listOf(QueryField("state"), QueryField("state.name"))),
            listOf("state.amount")
        ).assert()
            .isEqualTo("""{"state.name": 0, "_id": 0}""")
        native(
            Projection(include = listOf(QueryField("state")), exclude = listOf(QueryField("state.keywords")))
        ).assert()
            .isEqualTo("""{"state": 1, "_id": 0}""")
    }

    @Test
    fun `a record has the snapshot record's shape and exact decimals`() {
        val document = Document("_id", 1)
            .append("state", Document("amount", Decimal128(BigDecimal("0.10"))))
            .append("last_event_time", 2L)
            .append("tenant_id", "t")
            .append("owner_id", null)
            .append("tags", Document("region", listOf("east")))
        MongoSnapshotDocuments.record(document).toString().assert().isEqualTo(
            """{"tenantId":"t","ownerId":null,"tags":{"region":["east"]},"lastEventTime":2,"state":{"amount":0.10}}""",
        )
        val projected = MongoProjections.project(
            admitted(
                Projection(
                    include = listOf(QueryField("state.lines.sku")),
                    exclude = listOf(QueryField("state.lines.price"))
                )
            ),
            MongoSnapshotDocuments.record(
                Document(
                    "state",
                    Document("lines", listOf(7, Document("sku", "A").append("price", 1), Document("price", 2))),
                ),
            ),
        )
        projected.toString().assert().isEqualTo("""{"state":{"lines":[{},{"sku":"A"},{}]}}""")
    }

    @Test
    fun `a timeout is whole milliseconds, at least one, and MongoDB's expiry is the query timeout`() {
        MongoSnapshotQueryBackend.maxTimeMillis(Duration.ofNanos(1)).assert().isEqualTo(1L)
        MongoSnapshotQueryBackend.maxTimeMillis(Duration.ofMillis(5)).assert().isEqualTo(5L)
        MongoSnapshotQueryBackend.maxTimeMillis(Duration.ofNanos(5_000_001)).assert().isEqualTo(6L)
        val timeout = Duration.ofMillis(5)
        val server = ServerAddress()
        fun command(code: Int) = MongoCommandException(
            BsonDocument.parse("""{"ok": 0, "code": $code, "errmsg": "e"}"""),
            server
        )
        fun failure(timeout: Duration?, error: RuntimeException): Throwable? =
            runCatching { MongoSnapshotQueryBackend.timed(timeout) { throw error } }.exceptionOrNull()
        (failure(timeout, MongoExecutionTimeoutException(50, "expired")) as QueryTimeoutException).timeout
            .assert().isEqualTo(timeout)
        (failure(timeout, command(50)) is QueryTimeoutException).assert().isTrue()
        (failure(timeout, command(2)) is MongoCommandException).assert().isTrue()
        (
            failure(
                null,
                MongoExecutionTimeoutException(50, "expired")
            ) is MongoExecutionTimeoutException
            ).assert().isTrue()
        (failure(null, command(50)) is MongoCommandException).assert().isTrue()
        MongoSnapshotQueryBackend.timed(timeout) { 1 }.assert().isEqualTo(1)
    }
}
