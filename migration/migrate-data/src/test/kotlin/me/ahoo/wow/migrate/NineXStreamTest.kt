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

package me.ahoo.wow.migrate

import me.ahoo.test.asserts.assert
import me.ahoo.wow.migrate.example.NineXExample
import org.bson.BsonUndefined
import org.bson.Document
import org.bson.types.Decimal128
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal
import java.util.Date

class NineXStreamTest {
    @Test
    fun `a 9x stream reads as the 9x serializer wrote it`() {
        val stream = NineXExample.stream("order-stream-v1.json")
        stream.id.assert().isEqualTo("0VY3Gwb94OmV00H")
        stream.aggregate.assert().isEqualTo(NineXAggregate("example-service", "order"))
        stream.aggregate.toString().assert().isEqualTo("example-service.order")
        stream.aggregateId.assert().isEqualTo(NineXAggregateId("0VY3GwaK4OmV00E", "(0)"))
        stream.aggregateId.toString().assert().isEqualTo("(0)/0VY3GwaK4OmV00E")
        stream.ownerId.assert().isEqualTo("customer-1")
        stream.spaceId.assert().isEmpty()
        stream.version.assert().isEqualTo(1)
        stream.commandName.assert().isEqualTo("create_order")
        stream.events.single().name.assert().isEqualTo("order_created")
        stream.events.single().revision.assert().isEqualTo("0.0.1")
        stream.events.single().body.get("items").get(0).get("price").decimalValue().toPlainString()
            .assert().isEqualTo("12.50")
        stream.createTime.assert().isEqualTo(1792004186903)
    }

    @Test
    fun `a MongoDB document reads by its _id, and defaults what 9x left out`() {
        val stream = NineXStream.parse(
            """
            {"_id":"s1","contextName":"c","aggregateName":"a","aggregateId":"i","tenantId":"t","commandId":"cmd",
             "version":2,"createTime":5,"header":{"upstream_name":" "},"body":[{"name":"e","body":{"x":1}},{"name":"f"}]}
            """.trimIndent(),
        )
        stream.id.assert().isEqualTo("s1")
        stream.ownerId.assert().isEmpty()
        stream.requestId.assert().isEmpty()
        stream.commandName.assert().isNull()
        stream.events.map { it.revision }.assert().containsExactly("0.0.1", "0.0.1")
        stream.events[1].body.isObject.assert().isTrue()
        stream.events[0].id.assert().isEmpty()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"id":"s","aggregateName":"a","aggregateId":"i","tenantId":"t","commandId":"c","version":1,"createTime":1,"body":[{"name":"e"}]}""",
            """{"id":"s","contextName":"c","aggregateName":"a","aggregateId":"i","tenantId":"t","commandId":"c","createTime":1,"body":[{"name":"e"}]}""",
            """{"id":"s","contextName":"c","aggregateName":"a","aggregateId":"i","tenantId":"t","commandId":"c","version":1,"body":[{"name":"e"}]}""",
            """{"id":"s","contextName":"c","aggregateName":"a","aggregateId":"i","tenantId":"t","commandId":"c","version":1,"createTime":1,"body":[]}""",
            """{"id":"s","contextName":"c","aggregateName":"a","aggregateId":"i","tenantId":"t","commandId":"c","version":1,"createTime":1,"body":[{}]}""",
            """{"contextName":"c"}""",
        ],
    )
    fun `a stream missing what the import needs fails naming it`(text: String) {
        assertThrows<MigrationException> { NineXStream.parse(text) }
    }

    @Test
    fun `a stream is a JSON object`() {
        assertThrows<IllegalArgumentException> { NineXStream.parse("[]") }
    }

    @Test
    fun `framework headers are told apart from request facts`() {
        NineXHeaders.isFramework("trace_id").assert().isTrue()
        NineXHeaders.isFramework("command_operator").assert().isTrue()
        NineXHeaders.isFramework("command_wait_endpoint").assert().isTrue()
        NineXHeaders.isFramework("app_id").assert().isFalse()
    }

    @Test
    fun `aggregate ids order by id, then tenant`() {
        (NineXAggregateId("a", "t2") < NineXAggregateId("b", "t1")).assert().isTrue()
        (NineXAggregateId("a", "t1") < NineXAggregateId("a", "t2")).assert().isTrue()
        assertThrows<IllegalArgumentException> { NineXAggregate(" ", "a") }
        assertThrows<IllegalArgumentException> { NineXAggregate("c", " ") }
    }

    @Test
    fun `the tail's index is told by its key, however its numbers were written`() {
        MongoNineXEventSource.isCommitTimeKey(Document("createTime", 1).append("_id", 1)).assert().isTrue()
        MongoNineXEventSource.isCommitTimeKey(Document("createTime", 1.0).append("_id", 1L)).assert().isTrue()
        MongoNineXEventSource.isCommitTimeKey(Document("_id", 1).append("createTime", 1)).assert().isFalse()
        MongoNineXEventSource.isCommitTimeKey(Document("createTime", -1).append("_id", 1)).assert().isFalse()
        MongoNineXEventSource.isCommitTimeKey(Document("createTime", "hashed").append("_id", 1)).assert().isFalse()
        MongoNineXEventSource.isCommitTimeKey(Document("createTime", 1)).assert().isFalse()
        MongoNineXEventSource.isCommitTimeKey(null).assert().isFalse()
    }

    @Test
    fun `BSON values read as exact JSON`() {
        val id = ObjectId()
        val node = MongoNineXEventSource.toNode(
            Document("text", "a")
                .append("flag", true)
                .append("int", 1)
                .append("long", 2L)
                .append("double", 1.5)
                .append("decimal", Decimal128(BigDecimal("12.50")))
                .append("date", Date(7))
                .append("objectId", id)
                .append("list", listOf(1, "b"))
                .append("missing", null)
                .append("undefined", BsonUndefined())
                .append("other", 'c'),
        )
        node.get("text").stringValue().assert().isEqualTo("a")
        node.get("flag").booleanValue().assert().isTrue()
        node.get("int").intValue().assert().isEqualTo(1)
        node.get("long").longValue().assert().isEqualTo(2L)
        node.get("double").doubleValue().assert().isEqualTo(1.5)
        node.get("decimal").decimalValue().toPlainString().assert().isEqualTo("12.50")
        node.get("date").longValue().assert().isEqualTo(7L)
        node.get("objectId").stringValue().assert().isEqualTo(id.toHexString())
        node.get("list").size().assert().isEqualTo(2)
        node.get("missing").isNull.assert().isTrue()
        node.get("undefined").isNull.assert().isTrue()
        node.get("other").stringValue().assert().isEqualTo("c")
    }
}
