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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.ExecutionLease
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Deep review r1 (runtime log, the store's half): an append without the lease reserves its one-sequence block in one
 * write and inserts its stream in the next, and `appendedElsewhere` names the partition when the block is first
 * listed, whether the stream is in the head yet or not. A caller that reads the head when named (a node's probe,
 * `HandoffProber`) finds nothing past its cursor between the two writes, and the stream that lands next is not named
 * again: the node's tails read it only at their backstop, 10 s later (`HandoffForeignReservationTest` in `:runtime`).
 */
class MongoForeignReservationTest : MongoLogFixtures() {
    @Test
    fun `an entry that lands after its reservation was named should be named again once it is in the head`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            val type = AggregateType(context, "order")
            val owner = MongoLogFixture.log(1, context)
            val cursor = (owner.append(stream("a", 1, aggregate = type)) as AppendOutcome.Committed).position.sequence
            owner.appendedElsewhere(mapOf(0 to cursor)).assert().isEmpty()
            // Another node, its stream's insert held after its block is reserved.
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val degraded = MongoEventLog(
                faulty { document, write ->
                    if (document.getString(MongoEventLog.GAP) == null && held.count > 0) {
                        held.countDown()
                        release.await(TAIL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    }
                    write()
                },
                MongoLogFixture.DATABASE,
                context,
                1,
            )
            val appended = async(Dispatchers.IO) {
                withContext(ExecutionLease.NOT_HELD) { degraded.append(stream("b", 1, aggregate = type)) }
            }
            held.await(TAIL_TIMEOUT_MS, TimeUnit.MILLISECONDS).assert().isTrue()
            // The probe between the reservation and the insert: the partition is named, and its head has not moved.
            owner.appendedElsewhere(mapOf(0 to cursor)).assert().containsExactly(0)
            owner.head(0).assert().isEqualTo(cursor)
            release.countDown()
            val landed = (appended.await() as AppendOutcome.Committed).position.sequence
            owner.head(0).assert().isEqualTo(landed)
            // The caller read the head when named and found nothing: what has landed since is past its cursor.
            owner.appendedElsewhere(mapOf(0 to cursor)).assert()
                .describedAs("the partitions named once the stream of the block named before has landed past $cursor")
                .containsExactly(0)
        }
}
