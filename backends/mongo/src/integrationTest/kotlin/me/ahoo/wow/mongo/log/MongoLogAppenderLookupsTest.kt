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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.log.Append
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import org.junit.jupiter.api.Test

/**
 * A lookup that fails after a group's insert wrote part of it: the call returns an outcome for every stream, never
 * throws over what the insert committed (EventLog.appendAll). The refused stream's lookup is the event log contract's
 * case ([me.ahoo.wow.tck.log.EventLogSpec]); this is the lookup of whether a stream landed.
 */
class MongoLogAppenderLookupsTest : MongoLogFixtures() {
    @Test
    fun `a lookup of whether a stream landed that fails leaves it unknown, and the call goes on`(): Unit =
        runBlocking {
            // The driver refuses the oversized document as it reaches it, after the batches before it went: whether
            // each stream landed is looked up, and the first lookup fails. A batch before it may have gone, so the
            // stream is unknown, never thrown over what that batch committed.
            val context = MongoLogFixture.newContext()
            var wrote = false
            var failed = false
            val client = faulty(
                many = { documents, insert ->
                    try {
                        insert(documents)
                    } finally {
                        wrote = true
                    }
                },
                find = {
                    if (wrote && !failed) {
                        failed = true
                        throw MongoException(QUOTA_EXCEEDED, "failed once after a write")
                    }
                },
            )
            val log = MongoEventLog(client, MongoLogFixture.DATABASE, context, 1).also { it.createSchema() }
            val type = AggregateType(context, "order")
            val before = stream(type.ref("tenant", "before"))
            val big = stream(type.ref("tenant", "big")).copyWith(
                events = listOf(EventRecord("x.order.Imported", 1, """{"blob":"${"x".repeat(OVERSIZED_BYTES)}"}""")),
            )
            val outcomes = log.appendAll(
                listOf(Append.of(before), Append.of(big), Append.of(stream(type.ref("tenant", "after")))),
            )
            failed.assert().isTrue()
            outcomes[0].assert().isInstanceOf(AppendOutcome.Unknown::class.java)
            outcomes[1].assert().isInstanceOf(AppendOutcome.Rejected::class.java)
            outcomes[2].assert().isInstanceOf(AppendOutcome.Committed::class.java)
            // Unknown, it is appended again with its request, as a caller retries it: in the log once either way.
            log.append(before).assert().isInstanceOfAny(
                AppendOutcome.Committed::class.java,
                AppendOutcome.AlreadyCommitted::class.java,
            )
            MongoLogFixture.log(1, context).read(before.ref).toList().size.assert().isEqualTo(1)
        }

    private companion object {
        /** A server error the driver does not take as a write of unknown outcome. */
        const val QUOTA_EXCEEDED = 12_501

        /** An event beyond the 16 MiB document limit. */
        const val OVERSIZED_BYTES = 17 * 1024 * 1024
    }
}
