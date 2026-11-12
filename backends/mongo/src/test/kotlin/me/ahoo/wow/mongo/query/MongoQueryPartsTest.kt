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
import com.mongodb.client.model.Filters
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.CursorPosition
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.spi.StorageTimeoutException
import me.ahoo.wow.spi.log.Partitioner
import org.bson.BsonDocument
import org.bson.BsonString
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
        assertThrownBy<IllegalArgumentException> { MongoCursorCodec.decode(MongoCursorCodec.encode(position), 0) }
        assertThrownBy<IllegalArgumentException> {
            MongoCursorCodec.encode(
                CursorPosition(List(Sort.MAX_FIELDS + 1) { 1 })
            )
        }
        val scalar = org.bson.RawBsonDocument(
            BsonDocument.parse("""{"values": 1}"""),
            org.bson.codecs.BsonDocumentCodec()
        ).byteBuffer.asNIO()
        assertThrownBy<IllegalArgumentException> {
            MongoCursorCodec.decode(ByteArray(scalar.remaining()).also { scalar.get(it) }, 1)
        }
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
        assertThrownBy<IllegalArgumentException> { MongoKeyset.checked(sort, listOf("n")) }
        assertThrownBy<IllegalArgumentException> { MongoKeyset.checked(sort, listOf("n", Document())) }
        MongoKeyset.checked(sort, listOf("n", "a")).assert().containsExactly("n", "a")
        MongoKeyset.sort(emptyList()).assert().isNull()
        MongoKeyset.valueAt(Document("state", Document("name", "n")), "state.name").assert().isEqualTo("n")
        MongoKeyset.valueAt(Document("state", "text"), "state.name").assert().isNull()
    }

    @Test
    fun `a cursor sort ending with the tenant and the aggregate ID reads the snapshot key`() {
        fun sort(vararg fields: Pair<String, Sort.Direction>) =
            fields.map { (path, direction) -> Sort(QueryField(path), direction) }
        val asc = Sort.Direction.ASC
        val desc = Sort.Direction.DESC
        val keyed = MongoSnapshotDocuments.keyed(sort("state.name" to desc, "tenant_id" to asc, "aggregate_id" to asc))
        keyed.sort.assert().isEqualTo(sort("state.name" to desc, "_id" to asc))
        keyed.values(listOf("n", "t", "a")).assert()
            .containsExactly("n", BsonDocument("t", BsonString("t")).append("a", BsonString("a")))
        // A position whose tenant or aggregate value is not a string is another query's or a damaged token.
        listOf(listOf("n", 1, "a"), listOf("n", "t", 2L), listOf("n", null, "a")).forEach { values ->
            val refused = runCatching { keyed.values(values) }.exceptionOrNull()
            (refused as QueryRequestException).violation.assert().isEqualTo(QueryViolation.InvalidCursor)
        }
        MongoSnapshotDocuments.keyed(sort("tenant_id" to desc, "aggregate_id" to desc)).sort
            .assert().isEqualTo(sort("_id" to desc))
        // Anything else is sorted as it is.
        listOf(
            sort("tenant_id" to asc, "aggregate_id" to desc),
            sort("aggregate_id" to asc, "tenant_id" to asc),
            sort("aggregate_id" to asc),
            sort(),
        ).forEach { other ->
            val unkeyed = MongoSnapshotDocuments.keyed(other)
            unkeyed.sort.assert().isEqualTo(other)
            unkeyed.values(other.map { "v" }).assert().isEqualTo(other.map { "v" })
        }
        MongoEventDocuments.keyed(sort("tenant_id" to asc, "aggregate_id" to asc)).sort
            .assert().isEqualTo(sort("tenant_id" to asc, "aggregate_id" to asc))
    }

    @Test
    fun `a stream query leaves out the log's own fields and is given the partitions of the aggregates it pins`() {
        val unread = MongoEventDocuments.unread
        MongoProjections.native(admitted(Projection.ALL), unread = unread).toBsonDocument().toJson().assert().isEqualTo(
            """{"partition": 0, "sequence": 0, "causation": 0, "headers": 0, "access_version": 0, "access_key": 0, "transport": 0, "_id": 0}""",
        )
        MongoProjections.native(admitted(Projection(include = listOf(QueryField("tenantId")))), unread = unread)
            .toBsonDocument().toJson().assert().isEqualTo("""{"tenant_id": 1, "_id": 0}""")
        val type = AggregateType("ctx", "order")
        val partitions = listOf("a", "b", "c").map { Partitioner.partitionOf(type.ref("t", it), 4) }.distinct()
        MongoEventStreams(type) { 4 }.keysOf("t", listOf("a", "b", "c"))!!.toBsonDocument().toJson().assert()
            .isEqualTo(Filters.`in`("partition", partitions).toBsonDocument().toJson())
        // A node without the context's log open has no partition count: the query is not given partitions.
        MongoEventStreams(type) { null }.keysOf("t", listOf("a")).assert().isNull()
        MongoSnapshotDocuments.keysOf("t", listOf("a")).assert().isNull()
        MongoSnapshotDocuments.unread.assert().isEmpty()
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
            Projection(exclude = listOf(QueryField("state.lines.sku"), QueryField("access"), QueryField("deleted")))
        ).assert()
            .isEqualTo("""{"access": 0, "deleted": 0, "_id": 0}""")
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
            .append("access", Document("region", listOf("east")))
        MongoSnapshotDocuments.record(document).toString().assert().isEqualTo(
            """{"tenantId":"t","ownerId":null,"access":{"region":["east"]},"lastEventTime":2,"state":{"amount":0.10}}""",
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
        (failure(timeout, MongoExecutionTimeoutException(50, "expired")) as StorageTimeoutException).timeout
            .assert().isEqualTo(timeout)
        (failure(timeout, command(50)) is StorageTimeoutException).assert().isTrue()
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
