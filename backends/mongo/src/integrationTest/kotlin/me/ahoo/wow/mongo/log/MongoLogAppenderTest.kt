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

import com.mongodb.MongoException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.bson.Document
import org.junit.jupiter.api.Test

/**
 * A group commit that fails once part of it may have committed: the call returns an outcome for every stream, and what
 * it committed is answered committed (EventLog.appendAll).
 */
class MongoLogAppenderTest {
    @Test
    fun `a group whose second collection's insert the server refuses keeps the first's commits, the rest unknown`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            val refused = MongoException(QUOTA_EXCEEDED, "refused")
            var inserts = 0
            val client = faulty(many = { documents, insert ->
                if (documents.none { it.containsKey(MongoEventLog.GAP) } && ++inserts == 2) throw refused
                insert(documents)
            })
            val log = MongoEventLog(client, MongoLogFixture.DATABASE, context, 1).also { it.createSchema() }
            val order = stream(AggregateType(context, "order").ref("tenant", "a"))
            val item = stream(AggregateType(context, "item").ref("tenant", "b"))
            val outcomes = log.appendAll(listOf(order to null, item to null))
            outcomes[0].assert().isInstanceOf(AppendOutcome.Committed::class.java)
            (outcomes[1] as AppendOutcome.Unknown).cause.assert().isSameAs(refused)
            MongoLogFixture.log(1, context).read(order.ref).toList().size.assert().isEqualTo(1)
        }

    @Test
    fun `a stream appended alone after part of its group committed is unknown when the server refuses it`(): Unit =
        runBlocking {
            // The driver refuses the oversized document as it reaches it, so the streams around it are appended
            // again, each alone: the last one's insert is refused once the first one committed.
            val context = MongoLogFixture.newContext()
            val refused = MongoException(QUOTA_EXCEEDED, "refused")
            val client = faulty { document: Document, insert: () -> Unit ->
                if (!document.containsKey(MongoEventLog.GAP) && document.toJson().contains("after")) throw refused
                insert()
            }
            val log = MongoEventLog(client, MongoLogFixture.DATABASE, context, 1).also { it.createSchema() }
            val type = AggregateType(context, "order")
            val big = oversized(stream(type.ref("tenant", "big")))
            val outcomes = log.appendAll(
                listOf(
                    stream(type.ref("tenant", "before")) to null,
                    big to null,
                    stream(type.ref("tenant", "after")) to null,
                    stream(type.ref("tenant", "last")) to null,
                ),
            )
            outcomes[0].assert().isInstanceOf(AppendOutcome.Committed::class.java)
            outcomes[1].assert().isInstanceOf(AppendOutcome.Rejected::class.java)
            // The refused stream and the one after it, which is not tried, are unknown.
            (outcomes[2] as AppendOutcome.Unknown).cause.assert().isSameAs(refused)
            (outcomes[3] as AppendOutcome.Unknown).cause.assert().isSameAs(refused)
            MongoLogFixture.log(1, context).read(type.ref("tenant", "last")).toList().assert().isEmpty()
        }

    @Test
    fun `a stream appended alone that the server refuses while nothing of the group committed is thrown`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            val refused = MongoException(QUOTA_EXCEEDED, "refused")
            val client = faulty { document: Document, insert: () -> Unit ->
                if (!document.containsKey(MongoEventLog.GAP) && document.toJson().contains("after")) throw refused
                insert()
            }
            val log = MongoEventLog(client, MongoLogFixture.DATABASE, context, 1).also { it.createSchema() }
            val type = AggregateType(context, "order")
            // The oversized stream is refused before anything is written, and the stream after it fails alone.
            val big = oversized(stream(type.ref("tenant", "big")))
            val streams = listOf(big to null, stream(type.ref("tenant", "after")) to null)
            assertThrownBy<MongoException> { runBlocking { log.appendAll(streams) } }.isSameAs(refused)
        }

    /** [stream] with an event beyond the document size limit. */
    private fun oversized(stream: EventStreamRecord) = stream.copyWith(
        events = listOf(EventRecord("x.aggregate.Imported", 1, """{"blob":"${"x".repeat(OVERSIZED_BYTES)}"}""")),
    )

    private fun stream(ref: AggregateRef) = EventStreamRecord(
        ref,
        1,
        "c-${ref.id}",
        "r-${ref.id}",
        "${ref.type}.Act",
        1,
        listOf(EventRecord("${ref.type}.Acted", 1, "{}")),
        Scope.NONE,
    )

    private companion object {
        /** A server error the driver does not take as a write of unknown outcome. */
        const val QUOTA_EXCEEDED = 12_501

        /** An event beyond the 16 MiB document limit. */
        const val OVERSIZED_BYTES = 17 * 1024 * 1024
    }
}
