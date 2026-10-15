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

package me.ahoo.wow.mongo.log

import com.mongodb.client.model.Filters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.mongo.MongoNames
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.tck.log.EventLogSpec
import org.junit.jupiter.api.Test

class MongoEventLogTest : EventLogSpec() {
    override fun createLog(context: String, partitions: Int): EventLog = MongoLogFixture.log(partitions, context)

    private fun collection(context: String, aggregate: String) = "wow_log__${context.replace('-', '_')}__$aggregate"

    @Test
    fun `creating the schema twice is harmless`() {
        val context = MongoLogFixture.newContext()
        MongoLogFixture.log(4, context, listOf("order"))
        MongoLogFixture.log(4, context, listOf("order")).partitions.assert().isEqualTo(4)
    }

    @Test
    fun `a log recorded in another format version is refused`() {
        val context = MongoLogFixture.newContext()
        MongoLogFixture.log(4, context)
        MongoLogFixture.bumpFormat(collection(context, "partition"))
        assertThrownBy<IllegalStateException> { MongoLogFixture.log(4, context) }
    }

    @Test
    fun `an aggregate collection recorded in another format version is refused`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        MongoLogFixture.log(4, context, listOf("order"))
        MongoLogFixture.bumpFormat(collection(context, "order"))
        assertThrownBy<IllegalStateException> { MongoLogFixture.log(4, context, listOf("order")) }
        val log = MongoLogFixture.log(4, context)
        assertThrownBy<IllegalStateException> {
            runBlocking { log.read(AggregateType(context, "order").ref("tenant", "a")).toList() }
        }
    }

    @Test
    fun `a log created by a build without format records is refused`() {
        val context = MongoLogFixture.newContext()
        MongoLogFixture.createUnrecorded(collection(context, "partition"))
        assertThrownBy<IllegalStateException> { MongoLogFixture.log(4, context) }
    }

    @Test
    fun `an aggregate named like the partition counters is refused`() {
        val context = MongoLogFixture.newContext()
        assertThrownBy<IllegalArgumentException> { MongoLogFixture.log(4, context, listOf("Partition")) }
    }

    @Test
    fun `two aggregates that take one collection name are refused`() {
        val context = MongoLogFixture.newContext()
        MongoLogFixture.log(4, context, listOf("order-item"))
        assertThrownBy<IllegalStateException> { MongoLogFixture.log(4, context, listOf("order_item")) }
    }

    @Test
    fun `a long aggregate name is cut and hashed as PostgreSQL's identifiers are`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val long = AggregateType(context, "A".repeat(80))
        val log = MongoLogFixture.log(1, context)
        log.append(stream("a", 1, aggregate = long)).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        log.append(stream("a", 1, aggregate = long)).assert().isEqualTo(AppendOutcome.Conflict(1))
        val name = MongoNames.of("wow_log", context, long.name)
        name.length.assert().isEqualTo(MongoNames.MAX_LENGTH)
        name.assert().startsWith(collection(context, "a").take(MongoNames.MAX_LENGTH - 9))
        MongoNames.limited(collection(context, "a".repeat(80)) + "x")
            .assert().isNotEqualTo(MongoNames.limited(collection(context, "a".repeat(80)) + "y"))
        MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE).listCollectionNames().toList()
            .assert().contains(name)
    }

    @Test
    fun `a tail finds the aggregate collections another log created after it started`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val reader = MongoLogFixture.log(1, context, listOf("order"))
        val writer = MongoLogFixture.log(1, context)
        val order = AggregateType(context, "order")
        val cart = AggregateType(context, "cart")
        writer.append(stream("a", 1, aggregate = order))
        reader.tail(0).first().stream.ref.assert().isEqualTo(order.ref("tenant", "a"))
        writer.append(stream("b", 1, aggregate = cart))
        writer.append(stream("a", 2, aggregate = order))
        val entries = withTimeout(TAIL_TIMEOUT_MS) { reader.tail(0).take(3).toList() }
        entries.map { it.stream.ref.type }.assert().containsExactly(order, cart, order)
        reader.findByRequestId("tenant", entries[1].stream.requestId)?.ref.assert().isEqualTo(cart.ref("tenant", "b"))
    }

    @Test
    fun `a tail stops at a missing sequence rather than skip it`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val log = MongoLogFixture.log(1, context)
        (1..3).forEach { log.append(stream("a", it, aggregate = AggregateType(context, "order"))) }
        MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE).getCollection(collection(context, "order"))
            .deleteOne(Filters.eq("sequence", 2L))
        withTimeoutOrNull(TAIL_STALL_MS) { log.tail(0).take(2).toList() }.assert().isNull()
        withTimeout(TAIL_TIMEOUT_MS) { log.tail(0).first() }.position.sequence.assert().isEqualTo(1L)
    }

    @Test
    fun `a payload decimal beyond 34 digits is refused at append, never rounded`(): Unit = runBlocking {
        val valid = stream("a", 1)
        val log = createLog(valid.ref.type.context, 1)
        val tooPrecise = valid.copy(
            events = listOf(EventRecord("x.aggregate.Priced", 1, """{"amount":1.2345678901234567890123456789012345}""")),
        )
        assertThrownBy<IllegalArgumentException> { runBlocking { log.append(tooPrecise) } }
        log.head(0).assert().isNull()
        log.read(tooPrecise.ref).toList().assert().isEmpty()
        // An integer of 35 digits has no exact Decimal128 of scale 0 either.
        val tooLarge = tooPrecise.copy(
            events = listOf(EventRecord("x.aggregate.Priced", 1, """{"amount":12345678901234567890123456789012345}""")),
        )
        assertThrownBy<IllegalArgumentException> { runBlocking { log.append(tooLarge) } }
        log.append(valid).assert().isInstanceOf(AppendOutcome.Committed::class.java)
    }

    @Test
    fun `a different partition count is refused`() {
        val context = MongoLogFixture.newContext()
        MongoLogFixture.log(4, context)
        assertThrownBy<IllegalStateException> { MongoLogFixture.log(8, context) }
    }

    private companion object {
        const val TAIL_STALL_MS = 500L
    }
}
