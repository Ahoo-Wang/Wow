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

package me.ahoo.wow.mongo.timer

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.timer.Timer
import org.junit.jupiter.api.Test

/**
 * Deep review r1 (MongoDB backend): the due read is one aggregation with a `$unionWith` per owned partition and per
 * timer collection, so a node that owns many partitions of a context with several aggregate types that schedule builds
 * a pipeline past MongoDB's 1,000 stages (`internalPipelineLengthLimit`), and the read fails on every poll: no timer
 * of that context fires. [me.ahoo.wow.mongo.log.MongoTailReads.headsOf] chunks its unions for this limit; the timer
 * store's due read does not.
 */
class MongoTimerDueStagesTest {
    @Test
    fun `a node that owns every partition of the default 64 reads the due timers of 16 aggregate types`(): Unit =
        runBlocking {
            val partitions = 64
            val context = MongoLogFixture.newContext("timer-stages")
            val store = MongoTimerStore(MongoLogFixture.client, MongoLogFixture.DATABASE, context, partitions)
                .also { it.createSchema() }
            val refs = (1..16).map { AggregateType(context, "type$it").ref("t", "a-$it") }
            refs.forEach { store.put(timer(it, partitions)).assert().isTrue() }

            // A single node owns every partition: 64 partitions x 16 collections = 1,024 unions.
            val due = store.due((0 until partitions).toSet(), Long.MAX_VALUE, 10)

            due.map { it.owner }.assert().containsExactlyInAnyOrderElementsOf(refs)
        }

    @Test
    fun `a node that owns 1024 partitions reads the due timers of one aggregate type`(): Unit = runBlocking {
        val partitions = 1024
        val context = MongoLogFixture.newContext("timer-stages")
        val store = MongoTimerStore(MongoLogFixture.client, MongoLogFixture.DATABASE, context, partitions)
            .also { it.createSchema() }
        val ref = AggregateType(context, "order").ref("t", "o-1")
        store.put(timer(ref, partitions)).assert().isTrue()

        val due = store.due((0 until partitions).toSet(), Long.MAX_VALUE, 10)

        due.map { it.owner }.assert().containsExactly(ref)
    }

    private fun timer(ref: AggregateRef, partitions: Int) = Timer(
        id = "$ref#k@1",
        owner = ref,
        key = "k",
        version = 1,
        partition = Partitioner.partitionOf(ref, partitions),
        rule = ScheduleRule.At(5),
        dueAt = 5,
        target = ref,
        commandType = "c.order.Cancel",
        body = "{}",
    )
}
